package pl.marcinmilkowski.word_sketch.indexer.hybrid;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.SentenceDocument.Token;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Builds precomputed collocations index from a hybrid Lucene index.
 * 
 * For each lemma in the corpus:
 * 1. Find all documents containing the lemma
 * 2. Extract tokens within window of each occurrence
 * 3. Aggregate cooccurrence counts
 * 4. Compute logDice association scores
 * 5. Keep top-K collocates by logDice
 * 6. Write to binary file with hash index for O(1) lookup
 * 
 * Usage:
 *   CollocationsBuilder builder = new CollocationsBuilder(indexPath, statsPath);
 *   builder.setWindowSize(5);
 *   builder.setTopK(100);
 *   builder.setMinFrequency(10);
 *   builder.build(outputPath);
 */
public class CollocationsBuilder {

    private static final Logger logger = LoggerFactory.getLogger(CollocationsBuilder.class);

    private static final int MAGIC_NUMBER = 0x434F4C4C; // "COLL"
    private static final int VERSION = 1;

    private final IndexReader reader;
    private final IndexSearcher searcher;
    private final StatisticsReader statsReader;
    private static final long HEADER_SIZE = 64;

    // Configuration
    private int windowSize = 5;
    private int topK = 100;
    private int minFrequency = 10;
    private int minCooccurrence = 2;
    private int threads = Runtime.getRuntime().availableProcessors();
    private int checkpointEvery = 5_000;
    private int batchSize = 1000;
    private boolean resume = false;

    /**
     * Create a collocations builder.
     * 
     * @param indexPath Path to hybrid Lucene index
     * @param statsPath Path to stats.bin or stats.tsv
     */
    public CollocationsBuilder(String indexPath, String statsPath) throws IOException {
        Path path = Paths.get(indexPath);
        this.reader = DirectoryReader.open(MMapDirectory.open(path));
        this.searcher = new IndexSearcher(reader);
        this.statsReader = new StatisticsReader(statsPath);

        logger.info("CollocationsBuilder initialized:");
        logger.info("  Index: {} sentences", reader.numDocs());
        logger.info("  Stats: {} lemmas, {} tokens",
            statsReader.getUniqueLemmaCount(), statsReader.getTotalTokens());
    }

    // Configuration setters
    public void setWindowSize(int size) { this.windowSize = size; }
    public void setTopK(int k) { this.topK = k; }
    public void setMinFrequency(int freq) { this.minFrequency = freq; }
    public void setMinCooccurrence(int cooc) { this.minCooccurrence = cooc; }
    public void setThreads(int n) { this.threads = n; }
    public void setCheckpointEvery(int n) { this.checkpointEvery = Math.max(100, n); }
    public void setBatchSize(int size) { this.batchSize = Math.max(1, size); }
    public void setResume(boolean resume) { this.resume = resume; }

    /**
     * Build collocations for all lemmas and write to binary file.
     */
    public void build(String outputPath) throws IOException, InterruptedException {
        long startTime = System.currentTimeMillis();

        String offsetsTmpPath = outputPath + ".offsets.tmp";
        Path outFile = Paths.get(outputPath);
        Path offsetsTmpFile = Paths.get(offsetsTmpPath);

        // Get all lemmas meeting frequency threshold
        List<String> lemmas = statsReader.getLemmasByMinFrequency(minFrequency);
        logger.info("Building collocations for {} lemmas (freq >= {})", lemmas.size(), minFrequency);
        logger.info("Configuration: window={}, top-K={}, min-cooc={}, threads={}",
            windowSize, topK, minCooccurrence, threads);

        // Streaming build: write entries to output file as we go, and stream the offset table to a temp file.
        // This avoids holding all CollocationEntry objects in memory and makes long builds recoverable.
        final Object writeLock = new Object();

        // Resume support: load already-written headwords from offsets tmp (if present)
        final Set<String> alreadyDone;
        final AtomicInteger entryCount;
        if (resume && java.nio.file.Files.exists(outFile) && java.nio.file.Files.exists(offsetsTmpFile)) {
            alreadyDone = new HashSet<>();

            int restoredCount = 0;
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(java.nio.file.Files.newInputStream(offsetsTmpFile)))) {
                // The first int is a checkpoint count, which may be stale if the process was killed.
                // For robustness, iterate until no more bytes remain (explicit end condition).
                dis.readInt();
                while (dis.available() > 0) {
                    int len = dis.readUnsignedShort();
                    byte[] bytes = dis.readNBytes(len);
                    alreadyDone.add(new String(bytes, StandardCharsets.UTF_8));
                    if (dis.available() >= Long.BYTES) {
                        dis.readLong();
                    } else {
                        break; // truncated tail — stop reading
                    }
                    restoredCount++;
                }
            } catch (EOFException eof) {
                // defensive: stop on EOF if file was truncated
            }

            entryCount = new AtomicInteger(restoredCount);
            logger.info("Resuming build: {} headwords already written (scanned {})", entryCount.get(), offsetsTmpPath);
        } else {
            alreadyDone = null;
            entryCount = new AtomicInteger(0);
        }

        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger withResults = new AtomicInteger(entryCount.get());

        try (RandomAccessFile outRaf = new RandomAccessFile(outputPath, "rw");
             FileChannel outChannel = outRaf.getChannel();
             RandomAccessFile offsetsRaf = new RandomAccessFile(offsetsTmpPath, "rw")) {

            if (resume && alreadyDone != null) {
                // Append mode
                if (outRaf.length() < HEADER_SIZE) {
                    throw new IOException("Cannot resume: output file is too small: " + outputPath);
                }
                outChannel.position(outRaf.length());
                offsetsRaf.seek(offsetsRaf.length());
            } else {
                // Fresh build: truncate and write placeholder header
                outRaf.setLength(0);
                outChannel.position(0);
                writeHeader(outChannel, 0, 0L, 0L);
                outChannel.position(HEADER_SIZE);

                offsetsRaf.setLength(0);
                offsetsRaf.seek(0);
                offsetsRaf.writeInt(0); // placeholder count
            }

            ExecutorService executor = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (String lemma : lemmas) {
                    if (alreadyDone != null && alreadyDone.contains(lemma)) {
                        int count = processed.incrementAndGet();
                        if (count % 1000 == 0) {
                            logger.info("Progress: {}/{} lemmas processed, {} with collocates",
                                count, lemmas.size(), withResults.get());
                        }
                        continue;
                    }

                    Future<?> future = executor.submit(() -> {
                        try {
                            CollocationEntry entry = buildForLemma(lemma);
                            if (entry != null && !entry.isEmpty()) {
                                synchronized (writeLock) {
                                    long offset = outChannel.position();
                                    writeEntry(outChannel, entry);
                                    writeOffsetRecord(offsetsRaf, entry.headword(), offset);

                                    int newCount = entryCount.incrementAndGet();
                                    withResults.incrementAndGet();

                                    // Periodically checkpoint the offset tmp count to enable resume after power loss
                                    if (newCount % checkpointEvery == 0) {
                                        long pos = offsetsRaf.getFilePointer();
                                        offsetsRaf.seek(0);
                                        offsetsRaf.writeInt(newCount);
                                        offsetsRaf.seek(pos);
                                        outChannel.force(false);
                                    }
                                }
                            }

                            int count = processed.incrementAndGet();
                            if (count % 1000 == 0) {
                                logger.info("Progress: {}/{} lemmas processed, {} with collocates",
                                    count, lemmas.size(), withResults.get());
                            }
                        } catch (Exception e) {
                            logger.error("Failed to build collocations for '{}'", lemma, e);
                        }
                    });
                    futures.add(future);
                }

                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    } catch (ExecutionException ee) {
                        logger.error("Task execution failed", ee.getCause());
                    }
                }
            } finally {
                executor.shutdown();
                if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                    executor.shutdownNow();
                }
            }

            // Finalize offset tmp count
            synchronized (writeLock) {
                long pos = offsetsRaf.getFilePointer();
                offsetsRaf.seek(0);
                offsetsRaf.writeInt(entryCount.get());
                offsetsRaf.seek(pos);
                outChannel.force(false);
            }

            logger.info("Completed: {}/{} lemmas have collocates", withResults.get(), lemmas.size());

            // Append offset table (temp file) to the output
            long dataEnd = outChannel.position();
            long offsetTableStart = dataEnd;
            long offsetTableSize;
            try (FileChannel offsetsChannel = java.nio.channels.FileChannel.open(offsetsTmpFile, StandardOpenOption.READ)) {
                offsetTableSize = offsetsChannel.size();
                long transferred = 0;
                while (transferred < offsetTableSize) {
                    transferred += offsetsChannel.transferTo(transferred, offsetTableSize - transferred, outChannel);
                }
            }

            long fileEnd = outChannel.position();

            // Write final header
            outChannel.position(0);
            writeHeader(outChannel, entryCount.get(), offsetTableStart, offsetTableSize);

            logger.info("Written:");
            logger.info("  Header: {} bytes", HEADER_SIZE);
            logger.info("  Data: {} bytes", dataEnd - HEADER_SIZE);
            logger.info("  Offset table: {} bytes", offsetTableSize);
            logger.info("  Total: {} bytes ({} MB)", fileEnd, fileEnd / (1024 * 1024));
        }

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("Build completed in {} minutes", String.format(Locale.ROOT, "%.1f", elapsed / 60000.0));
        logger.info("Output: {}", outputPath);
    }

    /**
     * Build relation-specific collocations and write to relation_collocations.bin.
     *
     * This method processes the corpus once, tracking collocations per (headword, relation) pair
     * based on simple POS patterns. For each lemma in the corpus, it finds collocates matching
     * the configured POS patterns for each relation.
     *
     * Focus relations:
     * - noun_modifiers (adj modifying noun)
     * - noun_verbs (verb collocates of noun)
     * - verb_nouns (noun collocates of verb)
     * - noun_compounds (noun compounds)
     * - adj_nouns (noun collocates of adjective)
     *
     * @param outputPath Path for the output file (relation_collocations.bin)
     * @param grammarConfig Grammar configuration with relation definitions
     */
    public void buildRelationCollocations(String outputPath, GrammarConfigLoader grammarConfig)
            throws IOException, InterruptedException {
        long startTime = System.currentTimeMillis();

        // Generate corpus UUID
        UUID corpusUuid = UUID.randomUUID();

        // Filter to simple POS-only relations (no copula handling for first implementation)
        // Include all relations that have a simple CQL pattern with just POS tag
        List<GrammarConfigLoader.RelationConfig> simpleRelations = grammarConfig.getRelations().stream()
            .filter(r -> {
                // Skip relations that require copula handling - they need special logic
                if (Boolean.TRUE.equals(r.usesCopula())) {
                    logger.debug("EXCLUDING relation {} - uses copula", r.id());
                    return false;
                }
                // Include relations with simple POS patterns
                String pattern = r.cqlPattern();
                if (pattern == null || pattern.isEmpty()) {
                    logger.debug("EXCLUDING relation {} - no pattern", r.id());
                    return false;
                }
                // Skip patterns with multiple elements or special operators
                // Simple pattern: just [tag=XX.*] with no alternation, optionals, or repetition
                if (pattern.contains("?") || pattern.contains("{") || pattern.contains("|") || pattern.contains("[")) {
                    // Check if it's a simple [tag=...] pattern without alternation
                    if (!pattern.matches("\\[tag=[^\\]|]+\\]\\]?")) {
                        logger.debug("EXCLUDING relation {} - complex pattern: {}", r.id(), pattern);
                        return false;
                    }
                }
                return true;
            })
            .toList();

        if (simpleRelations.isEmpty()) {
            throw new IllegalArgumentException("No simple POS-only relations found in grammar config");
        }

        logger.info("Building relation collocations for {} relations:", simpleRelations.size());
        for (var rel : simpleRelations) {
            logger.info("  {}: head={}, collocate={}, pattern={}",
                rel.id(), rel.headPos(), rel.collocatePos(), rel.cqlPattern());
        }

        // Precompile POS patterns for each relation
        Map<String, Pattern> relationPatterns = new HashMap<>();
        for (var rel : simpleRelations) {
            String posRegex = parsePosFromCql(rel.cqlPattern());
            relationPatterns.put(rel.id(), Pattern.compile(posRegex, Pattern.CASE_INSENSITIVE));
        }

        // Get all lemmas meeting frequency threshold
        List<String> lemmas = statsReader.getLemmasByMinFrequency(minFrequency);
        logger.info("Processing {} lemmas for {} relations", lemmas.size(), simpleRelations.size());

        // Build collocations for each (lemma, relation) pair
        List<RelationCollocationsEntry> entries = new ArrayList<>();
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger withResults = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<List<RelationCollocationsEntry>>> futures = new ArrayList<>();

        // Batch lemmas for parallel processing (honour configurable batchSize when set)
        int resolvedBatchSize = (batchSize > 0) ? Math.max(1, batchSize) : Math.max(1, lemmas.size() / (threads * 2));
        List<List<String>> batches = partitionList(lemmas, Math.min(resolvedBatchSize, lemmas.size()));

        try {
            for (List<String> batch : batches) {
                Future<List<RelationCollocationsEntry>> future = executor.submit(() -> {
                    List<RelationCollocationsEntry> batchEntries = new ArrayList<>();
                    for (String lemma : batch) {
                        List<RelationCollocationsEntry> lemmaEntries = buildRelationEntriesForLemma(
                            lemma, simpleRelations, relationPatterns, corpusUuid);
                        if (!lemmaEntries.isEmpty()) {
                            synchronized (batchEntries) {
                                batchEntries.addAll(lemmaEntries);
                            }
                        }
                        int count = processed.incrementAndGet();
                        if (count % 5000 == 0) {
                            logger.info("Progress: {}/{} lemmas processed", count, lemmas.size());
                        }
                    }
                    return batchEntries;
                });
                futures.add(future);
            }

            for (Future<List<RelationCollocationsEntry>> future : futures) {
                try {
                    List<RelationCollocationsEntry> batchEntries = future.get();
                    entries.addAll(batchEntries);
                    withResults.addAndGet(batchEntries.size());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                } catch (ExecutionException ee) {
                    logger.error("Failed to get batch results", ee.getCause());
                }
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.MINUTES);
        }

        // Sort entries by key for consistent output
        entries.sort(Comparator.comparing(RelationCollocationsEntry::key));

        // Write to file
        logger.info("Writing {} entries to {}", entries.size(), outputPath);
        writeRelationCollocationsFile(outputPath, entries, simpleRelations, corpusUuid);

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("Relation collocations build completed in {} minutes", String.format(Locale.ROOT, "%.1f", elapsed / 60000.0));
        logger.info("Output: {} ({} entries)", outputPath, entries.size());
    }

    /**
     * Build relation entries for a single lemma.
     */
    private List<RelationCollocationsEntry> buildRelationEntriesForLemma(
            String lemma,
            List<GrammarConfigLoader.RelationConfig> relations,
            Map<String, Pattern> relationPatterns,
            UUID corpusUuid) throws IOException {

        long headwordFreq = statsReader.getFrequency(lemma);
        if (headwordFreq == 0) return Collections.emptyList();

        // Find all documents containing this lemma
        TermQuery query = new TermQuery(new Term("lemma", lemma.toLowerCase()));
        final String lemmaLower = lemma.toLowerCase(Locale.ROOT);

        // Map: relationId -> (collocateLemma -> coocCount)
        Map<String, Map<String, Long>> relationCoocs = new HashMap<>();
        for (var rel : relations) {
            relationCoocs.put(rel.id(), new HashMap<>());
        }

        final int[] hitCount = new int[] {0};

        forEachMatchedSentence(query, tokens -> {
            hitCount[0]++;
            if (tokens.isEmpty()) return;

            // Find all positions of headword in this sentence
            List<Integer> headwordPositions = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                String tLemma = tokens.get(i).lemma();
                if (tLemma != null && lemmaLower.equals(tLemma.toLowerCase(Locale.ROOT))) {
                    headwordPositions.add(i);
                }
            }

            if (headwordPositions.isEmpty()) return;

            // For each relation, check collocates within window
            for (var rel : relations) {
                Pattern posPattern = relationPatterns.get(rel.id());
                int slop = rel.defaultSlop() > 0 ? rel.defaultSlop() : windowSize;

                Map<String, Long> coocs = relationCoocs.get(rel.id());

                for (int hwPos : headwordPositions) {
                    int start = Math.max(0, hwPos - slop);
                    int end = Math.min(tokens.size(), hwPos + slop + 1);

                    for (int i = start; i < end; i++) {
                        if (i != hwPos) {
                            Token token = tokens.get(i);
                            if (token.lemma() != null) {
                                String collLower = token.lemma().toLowerCase(Locale.ROOT);
                                if (!collLower.equals(lemmaLower)) {
                                    // Check POS tag matches pattern
                                    String posTag = token.tag();
                                    if (posTag != null && posPattern.matcher(posTag).matches()) {
                                        coocs.merge(collLower, 1L, Long::sum);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });

        if (hitCount[0] == 0) {
            return Collections.emptyList();
        }

        // Build entries for relations with collocations
        List<RelationCollocationsEntry> entries = new ArrayList<>();

        for (var rel : relations) {
            Map<String, Long> coocs = relationCoocs.get(rel.id());

            // Filter by minimum cooccurrence
            coocs.entrySet().removeIf(e -> e.getValue() < minCooccurrence);

            if (coocs.isEmpty()) continue;

            // Compute logDice and create collocation objects
            List<RelationCollocationEntry> collocations = new ArrayList<>();
            for (Map.Entry<String, Long> entry : coocs.entrySet()) {
                String collocateLemma = entry.getKey();
                long coocCount = entry.getValue();

                long collocateFreq = statsReader.getFrequency(collocateLemma);
                if (collocateFreq == 0) continue;

                double logDice = calculateLogDice(coocCount, headwordFreq, collocateFreq);

                // Get most frequent POS tag for collocate
                TermStatistics stats = statsReader.getStatistics(collocateLemma);
                String pos = getMostFrequentPos(stats);

                collocations.add(new RelationCollocationEntry(
                    collocateLemma, pos, coocCount, (float) logDice));
            }

            // Sort by logDice and keep top-K
            collocations.sort((a, b) -> Float.compare(b.logDice(), a.logDice()));
            if (collocations.size() > topK) {
                collocations = collocations.subList(0, topK);
            }

            entries.add(new RelationCollocationsEntry(
                lemma, rel.id(), headwordFreq, collocations, corpusUuid));
        }

        return entries;
    }

    /**
     * Write relation collocations to binary file.
     */
    private void writeRelationCollocationsFile(
            String outputPath,
            List<RelationCollocationsEntry> entries,
            List<GrammarConfigLoader.RelationConfig> relations,
            UUID corpusUuid) throws IOException {

        String offsetsTmpPath = outputPath + ".offsets.tmp";
        Path offsetsTmpFile = Paths.get(offsetsTmpPath);

        try (RandomAccessFile outRaf = new RandomAccessFile(outputPath, "rw");
             FileChannel outChannel = outRaf.getChannel();
             RandomAccessFile offsetsRaf = new RandomAccessFile(offsetsTmpPath, "rw")) {

            outRaf.setLength(0);
            outChannel.position(0);
            offsetsRaf.setLength(0);

            // Write placeholder header (will update later)
            ByteBuffer headerBuf = ByteBuffer.allocate(128);
            headerBuf.putInt(0x524C434C); // MAGIC "RLCL"
            headerBuf.putInt(1); // VERSION
            headerBuf.putInt(entries.size()); // entryCount
            headerBuf.putInt(relations.size()); // relationsCount
            byte[] uuidBytes = uuidToBytes(corpusUuid);
            headerBuf.put(uuidBytes);
            headerBuf.putLong(System.currentTimeMillis());
            headerBuf.putInt(windowSize);
            headerBuf.putInt(topK);
            headerBuf.putLong(0); // offsetTableOffset (placeholder)
            headerBuf.flip();
            outChannel.write(headerBuf);
            outChannel.position(outChannel.position() + 64); // Reserve space for relations section

            long dataStart = outChannel.position();

            // Write relations section header (offset from start of file)
            long relationsOffset = dataStart;
            ByteBuffer relationsBuf = ByteBuffer.allocate(4 + relations.size() * 128);
            relationsBuf.putInt(relations.size());
            for (var rel : relations) {
                byte[] idBytes = rel.id().getBytes(StandardCharsets.UTF_8);
                relationsBuf.putShort((short) idBytes.length);
                relationsBuf.put(idBytes);
                // Pad with zeros to fixed size for simplicity
                for (int i = 0; i < 126 - idBytes.length; i++) {
                    relationsBuf.put((byte) 0);
                }
            }
            relationsBuf.flip();
            outChannel.write(relationsBuf);

            // Write data entries
            Map<String, Long> offsetIndex = new HashMap<>();

            for (RelationCollocationsEntry entry : entries) {
                long offset = outChannel.position();
                String key = entry.key();
                offsetIndex.put(key, offset);

                writeRelationEntry(outChannel, entry);
            }

            long dataEnd = outChannel.position();

            // Write offset table - use streaming to avoid huge memory allocation
            long offsetTableStart = outChannel.position();
            // First write count
            ByteBuffer countBuf = ByteBuffer.allocate(4);
            countBuf.putInt(offsetIndex.size());
            countBuf.flip();
            outChannel.write(countBuf);

            // Then write each entry
            for (Map.Entry<String, Long> oe : offsetIndex.entrySet()) {
                byte[] keyBytes = oe.getKey().getBytes(StandardCharsets.UTF_8);
                ByteBuffer entryBuf = ByteBuffer.allocate(2 + keyBytes.length + 8);
                entryBuf.putShort((short) keyBytes.length);
                entryBuf.put(keyBytes);
                entryBuf.putLong(oe.getValue());
                entryBuf.flip();
                outChannel.write(entryBuf);
            }

            // Update header with actual offsets
            outChannel.position(0);
            ByteBuffer finalHeader = ByteBuffer.allocate(128);
            finalHeader.putInt(0x524C434C);
            finalHeader.putInt(1);
            finalHeader.putInt(entries.size());
            finalHeader.putInt(relations.size());
            finalHeader.put(uuidBytes);
            finalHeader.putLong(System.currentTimeMillis());
            finalHeader.putInt(windowSize);
            finalHeader.putInt(topK);
            finalHeader.putLong(offsetTableStart); // offset table position in file
            finalHeader.putLong(offsetTableStart - dataStart); // offsetTableSize
            finalHeader.putLong(relationsOffset); // relationsOffset
            finalHeader.flip();
            outChannel.write(finalHeader);

            outChannel.force(false);

            logger.info("Written relation collocations:");
            logger.info("  Header: 128 bytes");
            logger.info("  Data: {} bytes", dataEnd - dataStart);
            logger.info("  Offset table: {} bytes", offsetTableStart - dataEnd);
            logger.info("  Total: {} bytes", outChannel.position());
        }

        // Clean up temp file
        Files.deleteIfExists(offsetsTmpFile);
    }

    /**
     * Write a single relation collocation entry.
     */
    private void writeRelationEntry(FileChannel channel, RelationCollocationsEntry entry) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(32768);

        // Write key (lemma|relationId)
        String key = entry.key();
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        buf.putShort((short) keyBytes.length);
        buf.put(keyBytes);

        // Write headword frequency
        buf.putLong(entry.headwordFrequency());

        // Write collocations
        buf.putShort((short) entry.collocations().size());

        for (RelationCollocationEntry coll : entry.collocations()) {
            byte[] lemmaBytes = coll.collocate().getBytes(StandardCharsets.UTF_8);
            byte[] posBytes = coll.pos().getBytes(StandardCharsets.UTF_8);

            buf.putShort((short) lemmaBytes.length);
            buf.put(lemmaBytes);

            buf.putShort((short) posBytes.length);
            buf.put(posBytes);

            buf.putLong(coll.cooccurrence());
            buf.putFloat(coll.logDice());
        }

        buf.flip();
        channel.write(buf);
    }

    /**
     * Parse POS regex from CQL pattern.
     */
    private String parsePosFromCql(String cqlPattern) {
        if (cqlPattern == null || cqlPattern.isEmpty()) {
            return ".*";
        }
        // Extract tag=... pattern from CQL
        int tagStart = cqlPattern.indexOf("tag=");
        if (tagStart >= 0) {
            int tagEnd = cqlPattern.indexOf("]", tagStart);
            if (tagEnd < 0) tagEnd = cqlPattern.length();
            return cqlPattern.substring(tagStart + 4, tagEnd).trim();
        }
        return ".*";
    }

    /**
     * Iterate over sentences matching the provided TermQuery and invoke the consumer with the decoded token list.
     * The deprecated IndexSearcher.search(Query, Collector) API is used here and suppression is localized to this helper.
     */
    @SuppressWarnings("deprecation")
    private void forEachMatchedSentence(TermQuery query, Consumer<List<Token>> consumer) throws IOException {
        searcher.search(query, new SimpleCollector() {
            private BinaryDocValues tokensDv;
            @Override
            protected void doSetNextReader(LeafReaderContext context) throws IOException {
                this.tokensDv = context.reader().getBinaryDocValues("tokens");
            }
            @Override
            public ScoreMode scoreMode() {
                return ScoreMode.COMPLETE_NO_SCORES;
            }
            @Override
            public void collect(int doc) throws IOException {
                if (tokensDv == null || !tokensDv.advanceExact(doc)) return;
                BytesRef bytesRef = tokensDv.binaryValue();
                List<Token> tokens = TokenSequenceCodec.decode(bytesRef);
                consumer.accept(tokens);
            }
        });
    }

    /**
     * Partition a list into sublists.
     */
    private <T> List<List<T>> partitionList(List<T> list, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            batches.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return batches;
    }

    /**
     * Convert UUID to 16-byte array.
     */
    private static byte[] uuidToBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long mostSig = uuid.getMostSignificantBits();
        long leastSig = uuid.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (mostSig & 0xFF);
            mostSig >>= 8;
        }
        for (int i = 15; i >= 8; i--) {
            bytes[i] = (byte) (leastSig & 0xFF);
            leastSig >>= 8;
        }
        return bytes;
    }

    /**
     * Record for a single relation collocation entry.
     */
    private record RelationCollocationEntry(
        String collocate,
        String pos,
        long cooccurrence,
        float logDice
    ) {}

    /**
     * Record for a (lemma, relation) collocation entry.
     */
    private record RelationCollocationsEntry(
        String headword,
        String relationId,
        long headwordFrequency,
        List<RelationCollocationEntry> collocations,
        UUID corpusUuid
    ) {
        String key() {
            return headword.toLowerCase() + "|" + relationId;
        }
    }

    /**
     * Build collocations for a single lemma.
     */
    private CollocationEntry buildForLemma(String lemma) throws IOException {
        long headwordFreq = statsReader.getFrequency(lemma);
        if (headwordFreq == 0) return null;

        // Find all documents containing this lemma.
        // IMPORTANT: do NOT use searcher.search(..., Integer.MAX_VALUE) because it materializes a huge ScoreDoc[]
        // for frequent lemmas (e.g., "the"), which can OOM the JVM. Stream matches via a collector.
        TermQuery query = new TermQuery(new Term("lemma", lemma.toLowerCase()));
        Map<String, Long> cooccurrences = new HashMap<>();
        final String lemmaLower = lemma.toLowerCase(Locale.ROOT);
        final int[] hitCount = new int[] {0};

        forEachMatchedSentence(query, tokens -> {
            hitCount[0]++;
            if (tokens.isEmpty()) return;

            // Find all positions of headword in this sentence token sequence
            List<Integer> headwordPositions = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                String tLemma = tokens.get(i).lemma();
                if (tLemma != null && lemmaLower.equals(tLemma.toLowerCase(Locale.ROOT))) {
                    headwordPositions.add(i);
                }
            }

            if (headwordPositions.isEmpty()) return;

            // Collect collocates within window for each occurrence
            for (int hwPos : headwordPositions) {
                int start = Math.max(0, hwPos - windowSize);
                int end = Math.min(tokens.size(), hwPos + windowSize + 1);

                for (int i = start; i < end; i++) {
                    if (i != hwPos) {
                        String collocateLemma = tokens.get(i).lemma();
                        if (collocateLemma != null) {
                            String collLower = collocateLemma.toLowerCase(Locale.ROOT);
                            if (!collLower.equals(lemmaLower)) {
                                cooccurrences.merge(collLower, 1L, Long::sum);
                            }
                        }
                    }
                }
            }
        });

        if (hitCount[0] == 0) {
            return null;
        }

        // Filter by minimum cooccurrence
        cooccurrences.entrySet().removeIf(e -> e.getValue() < minCooccurrence);

        if (cooccurrences.isEmpty()) {
            return null;
        }

        // Compute logDice and create Collocation objects
        List<Collocation> collocations = new ArrayList<>();
        for (Map.Entry<String, Long> entry : cooccurrences.entrySet()) {
            String collocateLemma = entry.getKey();
            long coocCount = entry.getValue();

            long collocateFreq = statsReader.getFrequency(collocateLemma);
            if (collocateFreq == 0) continue;

            double logDice = calculateLogDice(coocCount, headwordFreq, collocateFreq);

            // Get most frequent POS tag
            TermStatistics stats = statsReader.getStatistics(collocateLemma);
            String pos = getMostFrequentPos(stats);

            collocations.add(new Collocation(
                collocateLemma, pos, coocCount, collocateFreq, (float) logDice));
        }

        // Sort by logDice descending and keep top-K
        Collections.sort(collocations);
        if (collocations.size() > topK) {
            collocations = collocations.subList(0, topK);
        }

        return new CollocationEntry(lemma, headwordFreq, collocations);
    }

    private void writeOffsetRecord(RandomAccessFile offsetsRaf, String headword, long offset) throws IOException {
        byte[] headwordBytes = headword.getBytes(StandardCharsets.UTF_8);
        if (headwordBytes.length > 65535) {
            throw new IOException("Headword too long: " + headword);
        }
        offsetsRaf.writeShort(headwordBytes.length);
        offsetsRaf.write(headwordBytes);
        offsetsRaf.writeLong(offset);
    }

    /**
     * Calculate logDice association score.
     */
    private double calculateLogDice(long cooccurrence, long freq1, long freq2) {
        if (cooccurrence <= 0 || freq1 <= 0 || freq2 <= 0) {
            return 0.0;
        }
        double dice = (2.0 * cooccurrence) / (freq1 + freq2);
        double logDice = Math.log(dice) / Math.log(2) + 14;
        return Math.max(0, Math.min(14, logDice));
    }

    /**
     * Get most frequent POS tag for a lemma.
     */
    private String getMostFrequentPos(TermStatistics stats) {
        if (stats == null || stats.posDistribution() == null || stats.posDistribution().isEmpty()) {
            return "";
        }
        return stats.posDistribution().entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("");
    }

    private void writeHeader(FileChannel channel, int entryCount, long offsetTableOffset, long offsetTableSize)
            throws IOException {
        var buffer = java.nio.ByteBuffer.allocate(64);
        buffer.putInt(MAGIC_NUMBER);
        buffer.putInt(VERSION);
        buffer.putInt(entryCount);
        buffer.putInt(windowSize);
        buffer.putInt(topK);
        buffer.putLong(statsReader.getTotalTokens());
        buffer.putLong(offsetTableOffset);
        buffer.putLong(offsetTableSize);
        buffer.flip();
        channel.write(buffer);
    }

    private void writeEntry(FileChannel channel, CollocationEntry entry) throws IOException {
        var buffer = java.nio.ByteBuffer.allocate(32768); // 32KB buffer

        // Write headword
        byte[] headwordBytes = entry.headword().getBytes(StandardCharsets.UTF_8);
        buffer.putShort((short) headwordBytes.length);
        buffer.put(headwordBytes);

        // Write frequency
        buffer.putLong(entry.headwordFrequency());

        // Write collocates
        buffer.putShort((short) entry.collocates().size());

        for (Collocation c : entry.collocates()) {
            byte[] lemmaBytes = c.lemma().getBytes(StandardCharsets.UTF_8);
            byte[] posBytes = c.pos().getBytes(StandardCharsets.UTF_8);

            buffer.put((byte) lemmaBytes.length);
            buffer.put(lemmaBytes);

            buffer.put((byte) posBytes.length);
            buffer.put(posBytes);

            buffer.putLong(c.cooccurrence());
            buffer.putLong(c.frequency());
            buffer.putFloat(c.logDice());
        }

        buffer.flip();
        channel.write(buffer);
    }

    public void close() throws IOException {
        reader.close();
        statsReader.close();
    }

    /**
     * Unified main method that dispatches based on first argument.
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--relation".equals(args[0])) {
            // Relation mode: --relation <indexPath> <outputPath> <grammarConfigPath> [options]
            if (args.length < 4) {
                logger.error("Usage: CollocationsBuilder --relation <indexPath> <outputPath> <grammarConfigPath> [options]");
                logger.error("Options:");
                logger.error("  --window N        Window size (default: 5)");
                logger.error("  --top-k N         Top-K collocates (default: 100)");
                logger.error("  --min-freq N      Minimum headword frequency (default: 10)");
                System.exit(1);
            }

            String indexPath = args[1];
            String outputPath = args[2];
            String grammarConfigPath = args[3];

            // Parse options
            int windowSize = 5;
            int topK = 100;
            int minFrequency = 10;

            int optIdx = 4;
            while (optIdx < args.length) {
                String opt = args[optIdx++];
                if ("--window".equals(opt) && optIdx < args.length) {
                    windowSize = Integer.parseInt(args[optIdx++]);
                } else if ("--top-k".equals(opt) && optIdx < args.length) {
                    topK = Integer.parseInt(args[optIdx++]);
                } else if ("--min-freq".equals(opt) && optIdx < args.length) {
                    minFrequency = Integer.parseInt(args[optIdx++]);
                } else {
                    logger.warn("Unknown option for --relation: {}", opt);
                }
            }

            // Load grammar config
            GrammarConfigLoader grammarConfig = new GrammarConfigLoader(java.nio.file.Paths.get(grammarConfigPath));

            // Build relation collocations
            CollocationsBuilder builder = new CollocationsBuilder(indexPath, indexPath + "/stats.bin");
            builder.setWindowSize(windowSize);
            builder.setTopK(topK);
            builder.setMinFrequency(minFrequency);

            logger.info("Building relation collocations...");
            logger.info("  Index: {}", indexPath);
            logger.info("  Output: {}", outputPath);
            logger.info("  Grammar: {}", grammarConfigPath);
            logger.info("  Window: {}", windowSize);
            logger.info("  Top-K: {}", topK);

            builder.buildRelationCollocations(outputPath, grammarConfig);

            builder.close();
            logger.info("Done!");
            return;
        }

        // Original main method logic
        if (args.length < 2) {
            logger.error("Usage: CollocationsBuilder <indexPath> <outputPath> [options]");
            logger.error("       CollocationsBuilder --relation <indexPath> <outputPath> <grammarConfigPath> [options]");
            logger.error("Options:");
            logger.error("  --window N        Window size (default: 5)");
            logger.error("  --top-k N         Top-K collocates (default: 100)");
            logger.error("  --min-freq N      Minimum headword frequency (default: 10)");
            logger.error("  --min-cooc N      Minimum cooccurrence (default: 2)");
            logger.error("  --threads N       Parallel threads (default: CPU cores)");
            logger.error("  --checkpoint N    Update resume checkpoint every N entries (default: 5000)");
            logger.error("  --resume true|false  Resume from existing outputPath.offsets.tmp (default: false)");
            System.exit(1);
        }

        String indexPath = args[0];
        String outputPath = args[1];

        // Parse options
        int window = 5;
        int topK = 100;
        int minFreq = 10;
        int minCooc = 2;
        int threads = Runtime.getRuntime().availableProcessors();
        int checkpointEvery = 5_000;
        boolean resume = false;

        for (int i = 2; i < args.length; i += 2) {
            if (i + 1 >= args.length) break;
            String opt = args[i];
            String val = args[i + 1];

            switch (opt) {
                case "--window" -> window = Integer.parseInt(val);
                case "--top-k" -> topK = Integer.parseInt(val);
                case "--min-freq" -> minFreq = Integer.parseInt(val);
                case "--min-cooc" -> minCooc = Integer.parseInt(val);
                case "--threads" -> threads = Integer.parseInt(val);
                case "--checkpoint" -> checkpointEvery = Integer.parseInt(val);
                case "--resume" -> resume = Boolean.parseBoolean(val);
                default -> logger.warn("Unknown option: {}", opt);
            }
        }

        // Determine stats path
        String statsPath = indexPath + "/stats.bin";
        if (!java.nio.file.Files.exists(Paths.get(statsPath))) {
            statsPath = indexPath + "/stats.tsv";
        }

        CollocationsBuilder builder = new CollocationsBuilder(indexPath, statsPath);
        builder.setWindowSize(window);
        builder.setTopK(topK);
        builder.setMinFrequency(minFreq);
        builder.setMinCooccurrence(minCooc);
        builder.setThreads(threads);
        builder.setCheckpointEvery(checkpointEvery);
        builder.setResume(resume);

        builder.build(outputPath);
        builder.close();
    }
}

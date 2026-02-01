package pl.marcinmilkowski.word_sketch.indexer.hybrid;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.SentenceDocument.Token;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
    private final TokenSequenceCodec codec = new TokenSequenceCodec();

    // Configuration
    private int windowSize = 5;
    private int topK = 100;
    private int minFrequency = 10;
    private int minCooccurrence = 2;
    private int batchSize = 1000;
    private int threads = Runtime.getRuntime().availableProcessors();

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
    public void setBatchSize(int size) { this.batchSize = size; }
    public void setThreads(int n) { this.threads = n; }

    /**
     * Build collocations for all lemmas and write to binary file.
     */
    public void build(String outputPath) throws IOException, InterruptedException {
        long startTime = System.currentTimeMillis();

        // Get all lemmas meeting frequency threshold
        List<String> lemmas = statsReader.getLemmasByMinFrequency(minFrequency);
        logger.info("Building collocations for {} lemmas (freq >= {})", lemmas.size(), minFrequency);
        logger.info("Configuration: window={}, top-K={}, min-cooc={}, threads={}",
            windowSize, topK, minCooccurrence, threads);

        // Process lemmas in parallel
        List<CollocationEntry> entries = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger withResults = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (String lemma : lemmas) {
            Future<?> future = executor.submit(() -> {
                try {
                    CollocationEntry entry = buildForLemma(lemma);
                    if (entry != null && !entry.isEmpty()) {
                        entries.add(entry);
                        withResults.incrementAndGet();
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

        // Wait for all tasks to complete
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                logger.error("Task execution failed", e);
            }
        }
        executor.shutdown();

        logger.info("Completed: {}/{} lemmas have collocates", withResults.get(), lemmas.size());

        // Sort by headword for binary search
        entries.sort(Comparator.comparing(CollocationEntry::headword));

        // Write to binary file
        writeCollocationsBinary(entries, outputPath);

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("Build completed in {:.1f} minutes", elapsed / 60000.0);
        logger.info("Output: {}", outputPath);
    }

    /**
     * Build collocations for a single lemma.
     */
    private CollocationEntry buildForLemma(String lemma) throws IOException {
        long headwordFreq = statsReader.getFrequency(lemma);
        if (headwordFreq == 0) return null;

        // Find all documents containing this lemma
        TermQuery query = new TermQuery(new Term("lemma", lemma.toLowerCase()));
        TopDocs topDocs = searcher.search(query, Integer.MAX_VALUE);

        if (topDocs.scoreDocs.length == 0) {
            return null;
        }

        // Aggregate cooccurrence counts
        Map<String, Long> cooccurrences = new HashMap<>();

        for (ScoreDoc hit : topDocs.scoreDocs) {
            List<Token> tokens = loadTokensFromDoc(hit.doc);
            if (tokens.isEmpty()) continue;

            // Find all positions of headword
            List<Integer> headwordPositions = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                if (lemma.equalsIgnoreCase(tokens.get(i).lemma())) {
                    headwordPositions.add(i);
                }
            }

            // Collect collocates within window
            for (int hwPos : headwordPositions) {
                int start = Math.max(0, hwPos - windowSize);
                int end = Math.min(tokens.size(), hwPos + windowSize + 1);

                for (int i = start; i < end; i++) {
                    if (i == hwPos) continue; // Skip headword itself

                    String collocateLemma = tokens.get(i).lemma();
                    if (collocateLemma == null || collocateLemma.equals(lemma)) continue;

                    cooccurrences.merge(collocateLemma.toLowerCase(), 1L, Long::sum);
                }
            }
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

    /**
     * Load tokens from a document.
     */
    private List<Token> loadTokensFromDoc(int docId) throws IOException {
        var leafReaders = reader.leaves();

        for (var leafContext : leafReaders) {
            int localDocId = docId - leafContext.docBase;
            if (localDocId >= 0 && localDocId < leafContext.reader().maxDoc()) {
                var binaryDocValues = leafContext.reader().getBinaryDocValues("tokens");
                if (binaryDocValues != null && binaryDocValues.advanceExact(localDocId)) {
                    BytesRef bytesRef = binaryDocValues.binaryValue();
                    return TokenSequenceCodec.decode(bytesRef);
                }
            }
        }

        return Collections.emptyList();
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

    /**
     * Write collocations to binary file with hash index.
     */
    private void writeCollocationsBinary(List<CollocationEntry> entries, String outputPath)
            throws IOException {
        logger.info("Writing {} entries to {}", entries.size(), outputPath);

        try (RandomAccessFile raf = new RandomAccessFile(outputPath, "rw");
             FileChannel channel = raf.getChannel()) {

            // Reserve space for header (we'll write it at the end)
            long headerSize = 64;
            channel.position(headerSize);

            // Write entries and build offset map
            Map<String, Long> offsetMap = new HashMap<>();
            long dataStart = headerSize;

            for (CollocationEntry entry : entries) {
                long offset = channel.position();
                offsetMap.put(entry.headword(), offset);
                writeEntry(channel, entry);
            }

            long dataEnd = channel.position();

            // Write offset table
            long offsetTableStart = dataEnd;
            writeOffsetTable(channel, offsetMap);
            long offsetTableEnd = channel.position();

            // Now write header
            channel.position(0);
            writeHeader(channel, entries.size(), offsetTableStart, offsetTableEnd - offsetTableStart);

            logger.info("Written:");
            logger.info("  Header: {} bytes", headerSize);
            logger.info("  Data: {} bytes", dataEnd - dataStart);
            logger.info("  Offset table: {} bytes", offsetTableEnd - offsetTableStart);
            logger.info("  Total: {} bytes ({} MB)", offsetTableEnd,
                offsetTableEnd / (1024 * 1024));
        }
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
            buffer.putFloat((float) c.logDice());
        }

        buffer.flip();
        channel.write(buffer);
    }

    private void writeOffsetTable(FileChannel channel, Map<String, Long> offsetMap) throws IOException {
        var buffer = java.nio.ByteBuffer.allocate(offsetMap.size() * 32);

        // Write simple linear table (lemma → offset)
        buffer.putInt(offsetMap.size());
        for (Map.Entry<String, Long> entry : offsetMap.entrySet()) {
            byte[] lemmaBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
            buffer.putShort((short) lemmaBytes.length);
            buffer.put(lemmaBytes);
            buffer.putLong(entry.getValue());
        }

        buffer.flip();
        channel.write(buffer);
    }

    public void close() throws IOException {
        reader.close();
        statsReader.close();
    }

    /**
     * Main method for CLI.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: CollocationsBuilder <indexPath> <outputPath> [options]");
            System.err.println("Options:");
            System.err.println("  --window N        Window size (default: 5)");
            System.err.println("  --top-k N         Top-K collocates (default: 100)");
            System.err.println("  --min-freq N      Minimum headword frequency (default: 10)");
            System.err.println("  --min-cooc N      Minimum cooccurrence (default: 2)");
            System.err.println("  --threads N       Parallel threads (default: CPU cores)");
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

        builder.build(outputPath);
        builder.close();
    }
}

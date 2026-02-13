package pl.marcinmilkowski.word_sketch.indexer.hybrid;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.MMapDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Comparator;
import java.util.Locale;
import java.util.PriorityQueue;

/**
 * Single-pass processor for CoNLL-U files that produces both:
 * - Lucene hybrid index (via HybridIndexer)
 * - Collocations binary file (via CollocationsBinWriter)
 *
 * This eliminates the need to read CoNLL-U twice (once for indexing, once for collocations),
 * reducing I/O by ~50% for large corpora.
 *
 * Usage:
 * <pre>
 *   try (SinglePassConlluProcessor processor = new SinglePassConlluProcessor(indexPath, collocationsPath)) {
 *       processor.processFile("corpus.conllu");
 *       // Or process directory of files:
 *       processor.processDirectory("corpus_dir/", "*.conllu");
 *   }
 * </pre>
 */
public class SinglePassConlluProcessor implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(SinglePassConlluProcessor.class);

    // Core components
    private final HybridIndexer indexer;
    private final AtomicInteger sentenceId = new AtomicInteger(0);
    private final AtomicLong tokenCount = new AtomicLong(0);
    
    // Collocations accumulation (from CollocationsBuilderV2)
    private final LongIntHashMap[] shardMaps;
    private final int numShards;
    private final int spillThreshold;
    private final Path workDir;
    private int runId = 0;
    
    // Configuration
    private int commitInterval = 50_000;
    private int windowSize = 5;
    private int topK = 100;
    private int minHeadwordFrequency = 10;
    private int minCooccurrence = 2;
    
    // Output paths
    private final String indexPath;
    private final String collocationsPath;

    /**
     * Create a single-pass processor with default settings.
     * 
     * @param indexPath Path for the hybrid index
     * @param collocationsPath Path for the collocations.bin output
     */
    public SinglePassConlluProcessor(String indexPath, String collocationsPath) throws IOException {
        this(indexPath, collocationsPath, 64, 2_000_000);
    }

    /**
     * Create a single-pass processor with custom shard/spill settings.
     * 
     * @param indexPath Path for the hybrid index
     * @param collocationsPath Path for the collocations.bin output
     * @param numShards Number of shards for collocation map (must be power of 2)
     * @param spillThreshold Max entries per shard before spilling to disk
     */
    public SinglePassConlluProcessor(String indexPath, String collocationsPath, 
                                      int numShards, int spillThreshold) throws IOException {
        this.indexPath = indexPath;
        this.collocationsPath = collocationsPath;
        this.numShards = numShards;
        this.spillThreshold = spillThreshold;
        
        // Initialize indexer
        this.indexer = new HybridIndexer(indexPath);
        
        // Initialize shard maps for collocations
        this.shardMaps = new LongIntHashMap[numShards];
        for (int i = 0; i < numShards; i++) {
            shardMaps[i] = new LongIntHashMap(1024 * 1024);
        }
        
        // Create work directory for spill files
        this.workDir = Paths.get(collocationsPath + ".work");
        this.workDir.toFile().mkdirs();
        
        logger.info("SinglePassConlluProcessor initialized:");
        logger.info("  Index: {}", indexPath);
        logger.info("  Collocations: {}", collocationsPath);
        logger.info("  Shards: {}, Spill threshold: {}", numShards, spillThreshold);
    }

    // Configuration setters
    
    public void setCommitInterval(int interval) {
        this.commitInterval = interval;
    }
    
    public void setWindowSize(int windowSize) {
        this.windowSize = windowSize;
    }
    
    public void setTopK(int topK) {
        this.topK = topK;
    }
    
    public void setMinHeadwordFrequency(int minFreq) {
        this.minHeadwordFrequency = minFreq;
    }
    
    public void setMinCooccurrence(int minCooc) {
        this.minCooccurrence = minCooc;
    }

    /**
     * Process a single CoNLL-U file.
     */
    public void processFile(String inputFile) throws IOException {
        Path path = Paths.get(inputFile);
        logger.info("Processing CoNLL-U file: {}", inputFile);
        
        long fileStartTime = System.currentTimeMillis();
        int fileSentenceCount = 0;
        long fileTokenCount = 0;

        // Use lenient UTF-8 decoder
        var decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith("?");
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(path), decoder))) {
            
            List<String[]> currentTokens = new ArrayList<>();
            String currentText = "";

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) {
                    // End of sentence
                    if (!currentTokens.isEmpty()) {
                        SentenceDocument sentence = buildSentence(currentTokens, currentText);
                        processSentence(sentence);
                        
                        fileSentenceCount++;
                        fileTokenCount += currentTokens.size();
                        tokenCount.addAndGet(currentTokens.size());

                        int totalSentences = sentenceId.get();
                        if (totalSentences > 0 && totalSentences % commitInterval == 0) {
                            indexer.commit();
                            long elapsed = System.currentTimeMillis() - fileStartTime;
                            double rate = tokenCount.get() / (elapsed / 1000.0);
                            logger.info("Progress: {} sentences, {} tokens ({} tok/s)",
                                totalSentences, tokenCount.get(), String.format("%.0f", rate));
                        }

                        currentTokens.clear();
                        currentText = "";
                    }
                } else if (line.startsWith("#")) {
                    // Comment line - extract sentence text
                    if (line.startsWith("# text =")) {
                        currentText = line.substring("# text =".length()).trim();
                    }
                } else {
                    // Token line
                    String[] fields = line.split("\t");
                    if (fields.length >= 4) {
                        currentTokens.add(fields);
                    }
                }
            }

            // Process remaining sentence
            if (!currentTokens.isEmpty()) {
                SentenceDocument sentence = buildSentence(currentTokens, currentText);
                processSentence(sentence);
                fileSentenceCount++;
                fileTokenCount += currentTokens.size();
                tokenCount.addAndGet(currentTokens.size());
            }
        }

        // Commit after each file
        indexer.commit();

        long elapsed = System.currentTimeMillis() - fileStartTime;
        logger.info("File complete: {} sentences, {} tokens in {}s",
            fileSentenceCount, fileTokenCount, elapsed / 1000);
    }

    /**
     * Process all CoNLL-U files in a directory.
     * 
     * @param directory Path to directory
     * @param pattern Glob pattern (e.g., "*.conllu")
     */
    public void processDirectory(String directory, String pattern) throws IOException {
        Path dir = Paths.get(directory);
        logger.info("Processing directory: {} (pattern: {})", directory, pattern);

        long startTime = System.currentTimeMillis();
        int fileCount = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, pattern)) {
            List<Path> files = new ArrayList<>();
            stream.forEach(files::add);
            files.sort(Comparator.comparing(Path::getFileName));

            logger.info("Found {} files to process", files.size());

            for (Path file : files) {
                fileCount++;
                logger.info("Processing file {}/{}: {}", fileCount, files.size(), file.getFileName());
                processFile(file.toString());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("Directory complete: {} files, {} sentences, {} tokens in {}s",
            fileCount, sentenceId.get(), tokenCount.get(), elapsed / 1000);
    }

    /**
     * Process a single sentence: index it and collect collocation pairs.
     */
    private void processSentence(SentenceDocument sentence) throws IOException {
        // 1. Index the sentence
        indexer.indexSentence(sentence);
        
        // 2. Collect collocation pairs
        List<SentenceDocument.Token> tokens = sentence.tokens();
        int len = tokens.size();
        
        // Get lemma IDs for all tokens
        int[] lemmaIds = new int[len];
        for (int i = 0; i < len; i++) {
            String lemma = tokens.get(i).lemma();
            String normalized = (lemma != null) ? lemma.toLowerCase(Locale.ROOT) : "";
            lemmaIds[i] = indexer.getLemmaIdAssigner().getOrAssignId(normalized);
        }
        
        // Collect pairs within window
        for (int i = 0; i < len; i++) {
            int headId = lemmaIds[i];
            
            int start = Math.max(0, i - windowSize);
            int end = Math.min(len, i + windowSize + 1);
            
            int shard = headId & (numShards - 1);
            LongIntHashMap map = shardMaps[shard];
            
            for (int j = start; j < end; j++) {
                if (j == i) continue;
                int collId = lemmaIds[j];
                if (collId == headId) continue;
                
                long key = (((long) headId) << 32) | (collId & 0xffffffffL);
                map.addTo(key, 1);
            }
        }
        
        // Check if we need to spill
        boolean needSpill = false;
        for (LongIntHashMap m : shardMaps) {
            if (m.size() >= spillThreshold) {
                needSpill = true;
                break;
            }
        }
        
        if (needSpill) {
            spillAllShards();
            runId++;
        }
    }

    /**
     * Spill all shard maps to disk.
     */
    private void spillAllShards() throws IOException {
        Path pairsDir = workDir.resolve("pairs");
        Files.createDirectories(pairsDir);
        
        for (int shard = 0; shard < shardMaps.length; shard++) {
            LongIntHashMap map = shardMaps[shard];
            if (map.isEmpty()) continue;

            // Extract dense arrays
            long[] keys = new long[map.size()];
            int[] values = new int[map.size()];
            final int[] idx = new int[] {0};
            map.forEach((k, v) -> {
                int i = idx[0]++;
                keys[i] = k;
                values[i] = v;
            });

            LongIntPairSort.sort(keys, values, 0, keys.length);

            Path shardDir = pairsDir.resolve(String.format(Locale.ROOT, "shard-%03d", shard));
            Files.createDirectories(shardDir);
            Path runFile = shardDir.resolve(String.format(Locale.ROOT, "run-%06d.bin", runId));
            PairRunIO.writeRun(runFile, keys, values, keys.length);

            map.clear();
        }

        logger.info("Spilled run {} (memory threshold reached)", runId);
    }

    /**
     * Build a SentenceDocument from parsed CoNLL-U tokens.
     */
    private SentenceDocument buildSentence(List<String[]> tokenLines, String text) {
        int id = sentenceId.incrementAndGet();
        
        SentenceDocument.Builder builder = SentenceDocument.builder()
            .sentenceId(id)
            .text(text);

        int position = 0;
        int currentOffset = 0;
        
        for (String[] fields : tokenLines) {
            try {
                // Skip multi-word tokens (e.g., "1-2")
                if (fields[0].contains("-") || fields[0].contains(".")) {
                    continue;
                }

                String word = fields[1];
                String lemma = fields.length > 2 ? fields[2] : word;
                String upos = fields.length > 3 ? fields[3] : "X";
                String xpos = fields.length > 4 ? fields[4] : upos;

                // Use XPOS if available, otherwise UPOS
                String tag = (xpos != null && !xpos.equals("_")) ? xpos : upos;
                
                // Fix null/underscore values
                if (lemma == null || lemma.equals("_")) lemma = word;
                if (tag == null || tag.equals("_")) tag = "X";

                // Calculate approximate offsets from text
                int startOffset = text.indexOf(word, currentOffset);
                if (startOffset < 0) startOffset = currentOffset;
                int endOffset = startOffset + word.length();
                currentOffset = endOffset;

                builder.addToken(position, word, lemma, tag, startOffset, endOffset);
                position++;
                
            } catch (NumberFormatException e) {
                // Skip invalid token IDs
            }
        }

        return builder.build();
    }

    /**
     * Write statistics file.
     */
    public void writeStatistics(String statsPath) throws IOException {
        indexer.writeStatistics(statsPath);
    }

    /**
     * Get total sentences indexed.
     */
    public int getSentenceCount() {
        return sentenceId.get();
    }

    /**
     * Get total tokens indexed.
     */
    public long getTokenCount() {
        return tokenCount.get();
    }

    @Override
    public void close() throws IOException {
        // 1. Final commit and close indexer
        indexer.commit();
        
        // 2. Final spill of remaining collocation pairs
        spillAllShards();
        runId++;
        
        // 3. Write statistics and lexicon
        String statsPath = indexPath + "/stats.tsv";
        indexer.writeStatistics(statsPath);
        
        // 4. Merge spilled runs and write collocations.bin
        Path lexiconPath = Paths.get(indexPath, "lexicon.bin");
        if (Files.exists(lexiconPath)) {
            try (LemmaLexiconReader lexicon = new LemmaLexiconReader(lexiconPath.toString())) {
                reduceAndWrite(lexicon);
            }
        } else {
            logger.warn("Lexicon file not found, skipping collocations.bin generation: {}", lexiconPath);
        }
        
        // 5. Close indexer
        indexer.close();
        
        // 6. Clean up work directory
        deleteWorkDirectory();
        
        logger.info("SinglePassConlluProcessor closed: {} sentences, {} tokens",
            sentenceId.get(), tokenCount.get());
    }

    /**
     * Merge spilled runs and write final collocations.bin.
     */
    private void reduceAndWrite(LemmaLexiconReader lexicon) throws IOException {
        Path pairsDir = workDir.resolve("pairs");
        if (!Files.exists(pairsDir)) {
            logger.info("No collocation pairs to reduce");
            return;
        }

        long droppedMissingHead = 0;
        long droppedMissingColl = 0;
        byte[] lemmaIndexedCache = new byte[Math.max(1, lexicon.size())];
        
        try (IndexReader reader = DirectoryReader.open(MMapDirectory.open(Paths.get(indexPath)));
             CollocationsBinWriter writer = new CollocationsBinWriter(
                collocationsPath, windowSize, topK, lexicon.getTotalTokens())) {
            
            for (int shard = 0; shard < numShards; shard++) {
                Path shardDir = pairsDir.resolve(String.format(Locale.ROOT, "shard-%03d", shard));
                if (!Files.exists(shardDir)) continue;

                List<Path> runFiles;
                try (var stream = Files.list(shardDir)) {
                    runFiles = stream
                        .filter(p -> p.getFileName().toString().endsWith(".bin"))
                        .sorted()
                        .toList();
                }
                if (runFiles.isEmpty()) continue;

                logger.info("Reducing shard {} ({} runs)", shard, runFiles.size());
                long[] dropped = reduceShard(runFiles, lexicon, reader, lemmaIndexedCache, writer);
                droppedMissingHead += dropped[0];
                droppedMissingColl += dropped[1];
            }

            writer.finalizeFile();
            logger.info("collocations.bin entries: {}", writer.getEntryCount());
            logger.info("Dropped candidates due to index lemma absence: heads={}, collocates={}", droppedMissingHead, droppedMissingColl);
        }
    }

    /**
     * Reduce a single shard by merging run files.
     */
    private long[] reduceShard(List<Path> runFiles, LemmaLexiconReader lexicon,
                               IndexReader reader,
                               byte[] lemmaIndexedCache,
                               CollocationsBinWriter writer) throws IOException {
        long droppedMissingHead = 0;
        long droppedMissingColl = 0;
        // Open cursors and prime them
        List<PairRunIO.RunCursor> cursors = new ArrayList<>(runFiles.size());
        try {
            for (Path p : runFiles) {
                PairRunIO.RunCursor c = PairRunIO.openCursor(p);
                if (c.advance()) {
                    cursors.add(c);
                } else {
                    c.close();
                }
            }

            PriorityQueue<PairRunIO.RunCursor> pq = new PriorityQueue<>(Comparator.comparingLong(c -> c.key));
            pq.addAll(cursors);

            int currentHeadId = -1;
            long currentHeadFreq = 0;

            PriorityQueue<CollocationsBuilderV2.Candidate> top = new PriorityQueue<>(Comparator.comparingDouble(c -> c.logDice));

            while (!pq.isEmpty()) {
                PairRunIO.RunCursor c = pq.poll();

                long key = c.key;
                int count = c.value;

                if (c.advance()) {
                    pq.add(c);
                } else {
                    c.close();
                }

                // Aggregate identical keys across cursors
                while (!pq.isEmpty() && pq.peek().key == key) {
                    PairRunIO.RunCursor c2 = pq.poll();
                    count += c2.value;
                    if (c2.advance()) {
                        pq.add(c2);
                    } else {
                        c2.close();
                    }
                }

                int headId = (int) (key >>> 32);
                int collId = (int) key;

                if (headId != currentHeadId) {
                    // flush previous head
                    if (currentHeadId >= 0 && !top.isEmpty()) {
                        CollocationsBuilderV2.Candidate[] arr = top.toArray(new CollocationsBuilderV2.Candidate[0]);
                        // sort descending by logDice
                        java.util.Arrays.sort(arr, (a, b) -> Float.compare(b.logDice, a.logDice));
                        writer.writeEntry(currentHeadId, currentHeadFreq, arr, arr.length, lexicon);
                    }
                    top.clear();

                    currentHeadId = headId;
                    if (headId >= 0 && headId < lexicon.size()) {
                        currentHeadFreq = lexicon.getFrequency(headId);
                    } else {
                        currentHeadFreq = 0;
                    }
                }

                if (count < minCooccurrence) {
                    continue;
                }
                if (currentHeadFreq < minHeadwordFrequency) {
                    continue;
                }
                if (collId < 0 || collId >= lexicon.size()) {
                    continue;
                }

                if (!isLemmaIndexed(headId, lexicon, reader, lemmaIndexedCache)) {
                    droppedMissingHead++;
                    continue;
                }

                if (!isLemmaIndexed(collId, lexicon, reader, lemmaIndexedCache)) {
                    droppedMissingColl++;
                    continue;
                }

                long collFreq = lexicon.getFrequency(collId);
                if (collFreq <= 0) {
                    continue;
                }

                float logDice = (float) calculateLogDice(count, currentHeadFreq, collFreq);

                CollocationsBuilderV2.Candidate cand = new CollocationsBuilderV2.Candidate(collId, count, collFreq, logDice);
                if (top.size() < topK) {
                    top.add(cand);
                } else if (top.peek().logDice < cand.logDice) {
                    top.poll();
                    top.add(cand);
                }
            }

            // flush last head
            if (currentHeadId >= 0 && !top.isEmpty()) {
                CollocationsBuilderV2.Candidate[] arr = top.toArray(new CollocationsBuilderV2.Candidate[0]);
                java.util.Arrays.sort(arr, (a, b) -> Float.compare(b.logDice, a.logDice));
                writer.writeEntry(currentHeadId, currentHeadFreq, arr, arr.length, lexicon);
            }

        } finally {
            for (PairRunIO.RunCursor c : cursors) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }

        return new long[] { droppedMissingHead, droppedMissingColl };
    }

    private boolean isLemmaIndexed(int lemmaId, LemmaLexiconReader lexicon, IndexReader reader, byte[] cache) throws IOException {
        if (lemmaId < 0 || lemmaId >= cache.length) {
            return false;
        }

        byte state = cache[lemmaId];
        if (state == 1) return true;
        if (state == 2) return false;

        String lemma = lexicon.getLemma(lemmaId);
        boolean present = lemma != null && !lemma.isEmpty() && reader.docFreq(new Term("lemma", lemma)) > 0;
        cache[lemmaId] = present ? (byte) 1 : (byte) 2;
        return present;
    }

    /**
     * Calculate logDice score.
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
     * Delete the work directory.
     */
    private void deleteWorkDirectory() {
        try {
            Files.walk(workDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException ignored) {
                    }
                });
        } catch (IOException ignored) {
        }
    }

    /**
     * CLI entry point for single-pass processing.
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: SinglePassConlluProcessor <input> <outputIndex> <collocationsPath> [options]");
            System.err.println();
            System.err.println("Arguments:");
            System.err.println("  <input>            Path to CoNLL-U file or directory");
            System.err.println("  <outputIndex>      Path for output hybrid index");
            System.err.println("  <collocationsPath> Path for collocations.bin output");
            System.err.println();
            System.err.println("Options:");
            System.err.println("  --window N         Window size (default: 5)");
            System.err.println("  --top-k N          Top-K collocates (default: 100)");
            System.err.println("  --min-freq N       Minimum headword frequency (default: 10)");
            System.err.println("  --min-cooc N       Minimum cooccurrence (default: 2)");
            System.err.println("  --shards N         Number of shards (power of 2, default: 64)");
            System.err.println("  --spill N          Spill threshold per shard (default: 2000000)");
            System.err.println("  --commit N         Commit interval (default: 50000)");
            System.err.println();
            System.err.println("Examples:");
            System.err.println("  SinglePassConlluProcessor corpus.conllu index/ collocations.bin");
            System.err.println("  SinglePassConlluProcessor corpus_dir/ index/ collocations.bin --window 5 --top-k 100");
            System.exit(1);
        }

        String input = args[0];
        String outputIndex = args[1];
        String collocationsPath = args.length > 2 ? args[2] : outputIndex + "/collocations.bin";

        // Default settings
        int windowSize = 5;
        int topK = 100;
        int minFreq = 10;
        int minCooc = 2;
        int shards = 64;
        int spillThreshold = 2_000_000;
        int commitInterval = 50_000;

        // Parse options
        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--window") && i + 1 < args.length) {
                windowSize = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--top-k") && i + 1 < args.length) {
                topK = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--min-freq") && i + 1 < args.length) {
                minFreq = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--min-cooc") && i + 1 < args.length) {
                minCooc = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--shards") && i + 1 < args.length) {
                shards = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--spill") && i + 1 < args.length) {
                spillThreshold = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--commit") && i + 1 < args.length) {
                commitInterval = Integer.parseInt(args[++i]);
            }
        }

        System.out.println("=== Single-Pass Processor ===");
        System.out.println("Input: " + input);
        System.out.println("Output index: " + outputIndex);
        System.out.println("Collocations: " + collocationsPath);
        System.out.println("Window: " + windowSize);
        System.out.println("Top-K: " + topK);
        System.out.println("Min freq: " + minFreq);
        System.out.println("Min cooc: " + minCooc);
        System.out.println("Shards: " + shards);
        System.out.println("Spill threshold: " + spillThreshold);
        System.out.println();

        long startTime = System.currentTimeMillis();

        try (SinglePassConlluProcessor processor = new SinglePassConlluProcessor(
                outputIndex, collocationsPath, shards, spillThreshold)) {
            
            processor.setWindowSize(windowSize);
            processor.setTopK(topK);
            processor.setMinHeadwordFrequency(minFreq);
            processor.setMinCooccurrence(minCooc);
            processor.setCommitInterval(commitInterval);
            
            Path inputPath = Paths.get(input);
            
            if (Files.isDirectory(inputPath)) {
                processor.processDirectory(input, "*.conllu");
            } else {
                processor.processFile(input);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println();
            System.out.println("=== Complete ===");
            System.out.println("Sentences: " + processor.getSentenceCount());
            System.out.println("Tokens: " + processor.getTokenCount());
            System.out.println("Time: " + (elapsed / 1000) + "s");
            System.out.println("Rate: " + String.format("%.0f", processor.getTokenCount() / (elapsed / 1000.0)) + " tokens/s");
        }
    }
}

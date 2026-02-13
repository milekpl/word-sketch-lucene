package pl.marcinmilkowski.word_sketch.indexer.hybrid;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hybrid indexer that creates sentence-per-document indexes.
 * 
 * Unlike the legacy token-per-document indexer, this creates one Lucene document
 * per sentence, with all tokens stored in DocValues for efficient retrieval.
 * 
 * Index structure:
 * - Each document = one sentence
 * - Fields:
 *   - sentence_id (stored, indexed as IntPoint for range queries)
 *   - text (stored, for display)
 *   - word (TextField with positions, for span queries on word forms)
 *   - lemma (TextField with positions, for span queries on lemmas)
 *   - tag (TextField with positions, for span queries on POS tags)
 *   - tokens (BinaryDocValues, encoded token sequence for extraction)
 *   - token_count (stored, for statistics)
 * 
 * Benefits:
 * - ~15x smaller index (one doc per sentence vs. one per token)
 * - SpanQueries work across token fields
 * - DocValues enable O(1) token lookup by position
 */
public class HybridIndexer implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(HybridIndexer.class);

    private final IndexWriter writer;
    private final Directory directory;
    private final StatisticsCollector statsCollector;
    private final LemmaIdAssigner lemmaIdAssigner;
    private final AtomicLong sentenceCount = new AtomicLong(0);
    private final AtomicLong tokenCount = new AtomicLong(0);

    // Thread-local token streams for reuse
    private final ThreadLocal<PositionalTokenStream> wordTokenStream = 
        ThreadLocal.withInitial(PositionalTokenStream::new);
    private final ThreadLocal<PositionalTokenStream> lemmaTokenStream = 
        ThreadLocal.withInitial(PositionalTokenStream::new);
    private final ThreadLocal<PositionalTokenStream> tagTokenStream = 
        ThreadLocal.withInitial(PositionalTokenStream::new);
    private final ThreadLocal<PositionalTokenStream> posGroupTokenStream = 
        ThreadLocal.withInitial(PositionalTokenStream::new);

    /**
     * Creates a new hybrid indexer.
     * 
     * @param indexPath Path to the index directory
     * @throws IOException if the index cannot be created
     */
    public HybridIndexer(String indexPath) throws IOException {
        this(indexPath, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Creates a new hybrid indexer with specified thread count.
     * 
     * @param indexPath Path to the index directory
     * @param numThreads Number of threads for concurrent indexing
     * @throws IOException if the index cannot be created
     */
    public HybridIndexer(String indexPath, int numThreads) throws IOException {
        Path path = Paths.get(indexPath);
        Files.createDirectories(path);

        // Use MMapDirectory for better performance on large indexes
        this.directory = MMapDirectory.open(path);

        // Configure analyzer - we use custom token streams, so StandardAnalyzer is just a placeholder
        Analyzer analyzer = new StandardAnalyzer();

        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        config.setRAMBufferSizeMB(512);  // 512MB RAM buffer for faster indexing
        config.setUseCompoundFile(false); // Disable compound file for better write performance
        
        // Enable concurrent merges
        config.setMaxBufferedDocs(IndexWriterConfig.DISABLE_AUTO_FLUSH);

        this.writer = new IndexWriter(directory, config);
        this.statsCollector = new StatisticsCollector();
        this.lemmaIdAssigner = new LemmaIdAssigner();

        log.info("Hybrid indexer initialized at: {} (threads: {})", indexPath, numThreads);
    }

    /**
     * Indexes a single sentence.
     * 
     * @param sentence The sentence to index
     * @throws IOException if indexing fails
     */
    public void indexSentence(SentenceDocument sentence) throws IOException {
        Document doc = createDocument(sentence);
        writer.addDocument(doc);

        // Collect statistics
        statsCollector.addSentence(sentence);

        long count = sentenceCount.incrementAndGet();
        tokenCount.addAndGet(sentence.tokenCount());

        if (count % 100_000 == 0) {
            log.info("Indexed {} sentences, {} tokens", count, tokenCount.get());
        }
    }

    /**
     * Indexes multiple sentences in batch.
     * 
     * @param sentences The sentences to index
     * @throws IOException if indexing fails
     */
    public void indexSentences(List<SentenceDocument> sentences) throws IOException {
        for (SentenceDocument sentence : sentences) {
            indexSentence(sentence);
        }
    }

    /**
     * Creates a Lucene document from a sentence.
     */
    private Document createDocument(SentenceDocument sentence) throws IOException {
        Document doc = new Document();

        // Sentence ID - stored and indexed for range queries
        doc.add(new StoredField("sentence_id", sentence.sentenceId()));
        doc.add(new IntPoint("sentence_id_point", sentence.sentenceId()));

        // Sentence text - stored only
        if (sentence.text() != null) {
            doc.add(new StoredField("text", sentence.text()));
        }

        // Token count - stored for quick access
        doc.add(new StoredField("token_count", sentence.tokenCount()));

        List<SentenceDocument.Token> tokens = sentence.tokens();
        if (tokens != null && !tokens.isEmpty()) {
            // Word field with positions - for span queries
            PositionalTokenStream wordStream = wordTokenStream.get();
            wordStream.setTokens(tokens);
            wordStream.setFieldType("word");
            doc.add(new TextField("word", wordStream));

            // Lemma field with positions - for span queries on lemmas
            PositionalTokenStream lemmaStream = lemmaTokenStream.get();
            lemmaStream.setTokens(tokens);
            lemmaStream.setFieldType("lemma");
            doc.add(new TextField("lemma", lemmaStream));

            // Tag field with positions - for span queries on POS tags
            PositionalTokenStream tagStream = tagTokenStream.get();
            tagStream.setTokens(tokens);
            tagStream.setFieldType("tag");
            doc.add(new TextField("tag", tagStream));

            // POS group field with positions - for broad POS category queries
            PositionalTokenStream posGroupStream = posGroupTokenStream.get();
            posGroupStream.setTokens(tokens);
            posGroupStream.setFieldType("pos_group");
            doc.add(new TextField("pos_group", posGroupStream));

            // Token sequence as binary DocValues - for efficient extraction
            BytesRef tokenBytes = TokenSequenceCodec.encode(tokens);
            doc.add(new BinaryDocValuesField("tokens", tokenBytes));

            // Compact lemma ID sequence as binary DocValues - for single-pass collocation builds
            int[] lemmaIds = new int[tokens.size()];
            for (int i = 0; i < tokens.size(); i++) {
                String lemma = tokens.get(i).lemma();
                String normalized = lemma != null ? lemma.toLowerCase(Locale.ROOT) : "";
                lemmaIds[i] = lemmaIdAssigner.getOrAssignId(normalized);
            }
            BytesRef lemmaIdBytes = LemmaIdsCodec.encode(lemmaIds);
            doc.add(new BinaryDocValuesField("lemma_ids", lemmaIdBytes));
        }

        return doc;
    }

    /**
     * Commits the index and builds statistics.
     * 
     * @throws IOException if commit fails
     */
    public void commit() throws IOException {
        writer.commit();
        log.info("Index committed: {} sentences, {} tokens", 
            sentenceCount.get(), tokenCount.get());
    }

    /**
     * Writes the term statistics to a separate file.
     * 
     * @param statsPath Path to write statistics (supports .tsv or .bin extension)
     * @throws IOException if writing fails
     */
    public void writeStatistics(String statsPath) throws IOException {
        // Always write TSV (human-readable)
        statsCollector.writeToFile(statsPath);
        log.info("Statistics written to: {}", statsPath);
        
        // Also write binary format for fast loading
        String binPath = statsPath.endsWith(".tsv") 
            ? statsPath.replace(".tsv", ".bin")
            : statsPath + ".bin";
        
        try {
            statsCollector.writeBinaryFile(binPath);
            log.info("Binary statistics written to: {}", binPath);
        } catch (Exception e) {
            log.warn("Failed to write binary statistics: {}", e.getMessage());
        }

        // Write lemma lexicon (IDs + frequencies + most frequent POS) for single-pass collocations
        try {
            java.nio.file.Path statsFile = java.nio.file.Paths.get(statsPath);
            java.nio.file.Path lexiconPath = (statsFile.getParent() != null)
                ? statsFile.getParent().resolve("lexicon.bin")
                : java.nio.file.Paths.get("lexicon.bin");
            LemmaLexiconWriter.writeBinaryFile(
                lexiconPath.toString(),
                lemmaIdAssigner,
                statsCollector);
            log.info("Lexicon written to: {}", lexiconPath);
        } catch (Exception e) {
            log.warn("Failed to write lexicon: {}", e.getMessage());
        }
    }
    
    // Remove old convertTsvToBinary method

    /**
     * Gets the statistics collector for accessing term frequencies.
     */
    public StatisticsCollector getStatisticsCollector() {
        return statsCollector;
    }

    public LemmaIdAssigner getLemmaIdAssigner() {
        return lemmaIdAssigner;
    }

    /**
     * Gets the current sentence count.
     */
    public long getSentenceCount() {
        return sentenceCount.get();
    }

    /**
     * Gets the current token count.
     */
    public long getTokenCount() {
        return tokenCount.get();
    }

    @Override
    public void close() throws IOException {
        writer.close();
        directory.close();
        log.info("Hybrid indexer closed. Total: {} sentences, {} tokens",
            sentenceCount.get(), tokenCount.get());
    }

    /**
     * Collects term statistics during indexing.
     * Thread-safe for concurrent indexing.
     */
    public static class StatisticsCollector {
        private final ConcurrentHashMap<String, AtomicLong> lemmaFrequencies = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicLong> tagFrequencies = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>> lemmaTagDistribution = 
            new ConcurrentHashMap<>();
        private final AtomicLong totalTokens = new AtomicLong(0);
        private final AtomicLong totalSentences = new AtomicLong(0);

        /**
         * Adds statistics from a sentence.
         */
        public void addSentence(SentenceDocument sentence) {
            totalSentences.incrementAndGet();
            
            for (SentenceDocument.Token token : sentence.tokens()) {
                totalTokens.incrementAndGet();
                
                String lemma = token.lemma() != null ? token.lemma().toLowerCase(Locale.ROOT) : "";
                String tag = token.tag() != null ? token.tag().toUpperCase() : "";
                
                if (!lemma.isEmpty()) {
                    lemmaFrequencies.computeIfAbsent(lemma, k -> new AtomicLong(0)).incrementAndGet();
                }
                
                if (!tag.isEmpty()) {
                    tagFrequencies.computeIfAbsent(tag, k -> new AtomicLong(0)).incrementAndGet();
                }
                
                // Track lemma->tag distribution
                if (!lemma.isEmpty() && !tag.isEmpty()) {
                    lemmaTagDistribution
                        .computeIfAbsent(lemma, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(tag, k -> new AtomicLong(0))
                        .incrementAndGet();
                }
            }
        }

        /**
         * Gets the frequency of a lemma.
         */
        public long getLemmaFrequency(String lemma) {
            AtomicLong freq = lemmaFrequencies.get(lemma.toLowerCase(Locale.ROOT));
            return freq != null ? freq.get() : 0;
        }

        /**
         * Gets the frequency of a POS tag.
         */
        public long getTagFrequency(String tag) {
            AtomicLong freq = tagFrequencies.get(tag.toUpperCase());
            return freq != null ? freq.get() : 0;
        }

        /**
         * Returns the most frequent POS tag observed for a lemma.
         * Lemma lookup is case-insensitive.
         */
        public String getMostFrequentPos(String lemma) {
            if (lemma == null) return "";
            ConcurrentHashMap<String, AtomicLong> tagDist = lemmaTagDistribution.get(lemma.toLowerCase(Locale.ROOT));
            if (tagDist == null || tagDist.isEmpty()) return "";
            return tagDist.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue(java.util.Comparator.comparingLong(AtomicLong::get)))
                .map(java.util.Map.Entry::getKey)
                .orElse("");
        }

        /**
         * Gets the total token count.
         */
        public long getTotalTokens() {
            return totalTokens.get();
        }

        /**
         * Gets the total sentence count.
         */
        public long getTotalSentences() {
            return totalSentences.get();
        }

        /**
         * Gets the number of unique lemmas.
         */
        public int getUniqueLemmaCount() {
            return lemmaFrequencies.size();
        }

        /**
         * Writes statistics to a file.
         * Output format is TSV compatible with StatisticsReader:
         * lemma<TAB>totalFreq<TAB>docFreq<TAB>posDist
         */
        public void writeToFile(String path) throws IOException {
            Path statsPath = Paths.get(path);
            if (statsPath.getParent() != null) {
                Files.createDirectories(statsPath.getParent());
            }
            
            try (var writer = Files.newBufferedWriter(statsPath)) {
                // Header comments (parsed by StatisticsReader)
                writer.write("# Hybrid Index Statistics\n");
                writer.write(String.format("# Total sentences: %d\n", totalSentences.get()));
                writer.write(String.format("# Total tokens: %d\n", totalTokens.get()));
                writer.write(String.format("# Unique lemmas: %d\n", lemmaFrequencies.size()));
                writer.write(String.format("# Unique tags: %d\n", tagFrequencies.size()));
                writer.write("# Format: lemma<TAB>totalFreq<TAB>docFreq<TAB>posDist\n");
                
                // Write lemma frequencies sorted by frequency descending
                // Format: lemma<TAB>totalFreq<TAB>docFreq<TAB>TAG1:count1,TAG2:count2
                lemmaFrequencies.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                    .forEach(entry -> {
                        try {
                            String lemma = entry.getKey();
                            long freq = entry.getValue().get();
                            // docFreq = number of sentences containing this lemma
                            // For now, use a rough estimate based on frequency
                            int docFreq = (int) Math.min(freq, totalSentences.get());
                            
                            // Build POS distribution string
                            StringBuilder posDist = new StringBuilder();
                            ConcurrentHashMap<String, AtomicLong> tagDist = lemmaTagDistribution.get(lemma);
                            if (tagDist != null && !tagDist.isEmpty()) {
                                boolean first = true;
                                for (var tagEntry : tagDist.entrySet()) {
                                    if (!first) posDist.append(",");
                                    posDist.append(tagEntry.getKey())
                                           .append(":")
                                           .append(tagEntry.getValue().get());
                                    first = false;
                                }
                            }
                            
                            writer.write(String.format("%s\t%d\t%d\t%s\n", 
                                lemma, freq, docFreq, posDist.toString()));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            }
        }
        
        /**
         * Writes statistics to a binary file for fast loading.
         * Uses the same format as StatisticsIndexBuilder for compatibility.
         */
        public void writeBinaryFile(String path) throws IOException {
            Path binPath = Paths.get(path);
            if (binPath.getParent() != null) {
                Files.createDirectories(binPath.getParent());
            }
            
            try (var dos = new java.io.DataOutputStream(
                    new java.io.BufferedOutputStream(new java.io.FileOutputStream(binPath.toFile())))) {
                
                // Header (compatible with StatisticsReader)
                dos.writeInt(0x57534C53); // Magic: "WSLS"
                dos.writeInt(1);            // Version
                dos.writeLong(totalTokens.get());
                dos.writeLong(totalSentences.get());
                dos.writeInt(lemmaFrequencies.size());
                
                // Entries sorted by frequency descending
                lemmaFrequencies.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                    .forEach(entry -> {
                        try {
                            String lemma = entry.getKey();
                            long freq = entry.getValue().get();
                            int docFreq = (int) Math.min(freq, totalSentences.get());
                            
                            // Lemma
                            byte[] lemmaBytes = lemma.getBytes("UTF-8");
                            dos.writeShort(lemmaBytes.length);
                            dos.write(lemmaBytes);
                            
                            // Frequencies
                            dos.writeLong(freq);
                            dos.writeInt(docFreq);
                            
                            // POS distribution
                            ConcurrentHashMap<String, AtomicLong> tagDist = lemmaTagDistribution.get(lemma);
                            if (tagDist != null && !tagDist.isEmpty()) {
                                dos.writeShort(tagDist.size());
                                for (var tagEntry : tagDist.entrySet()) {
                                    byte[] tagBytes = tagEntry.getKey().getBytes("UTF-8");
                                    dos.writeByte(tagBytes.length);
                                    dos.write(tagBytes);
                                    dos.writeLong(tagEntry.getValue().get());
                                }
                            } else {
                                dos.writeShort(0); // No POS distribution
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            }
        }

        /**
         * Loads statistics from a file.
         */
        public static StatisticsCollector loadFromFile(String path) throws IOException {
            StatisticsCollector collector = new StatisticsCollector();
            
            try (var reader = Files.newBufferedReader(Paths.get(path))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#")) continue;
                    
                    String[] parts = line.split("\t");
                    if (parts.length >= 2) {
                        String lemma = parts[0];
                        long freq = Long.parseLong(parts[1]);
                        collector.lemmaFrequencies.put(lemma, new AtomicLong(freq));
                    }
                }
            }
            
            return collector;
        }
    }
}

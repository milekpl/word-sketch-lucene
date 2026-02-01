package pl.marcinmilkowski.word_sketch.indexer.hybrid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Fast reader for term statistics with O(1) lookups.
 * 
 * Statistics are loaded into memory for instant access during query execution.
 * For a 250M token corpus with ~2M unique lemmas, this uses ~100-200MB RAM.
 * 
 * Supports two loading modes:
 * - Binary file: Fast loading, compact format
 * - TSV file: Human-readable, slower loading
 */
public class StatisticsReader implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(StatisticsReader.class);

    private static final int MAGIC_NUMBER = 0x57534C53; // "WSLS"
    private static final int VERSION = 1;

    private final Map<String, TermStatistics> statisticsMap = new HashMap<>();
    private long totalTokens = 0;
    private long totalSentences = 0;

    /**
     * Creates a StatisticsReader and loads statistics from a file.
     * 
     * @param path Path to the statistics file (.bin or .tsv)
     * @throws IOException if loading fails
     */
    public StatisticsReader(String path) throws IOException {
        Path filePath = Paths.get(path);
        if (!Files.exists(filePath)) {
            throw new FileNotFoundException("Statistics file not found: " + path);
        }

        if (path.endsWith(".bin")) {
            loadBinaryFile(filePath);
        } else {
            loadTsvFile(filePath);
        }

        log.info("Statistics loaded: {} lemmas, {} tokens from {}",
            statisticsMap.size(), totalTokens, path);
    }

    /**
     * Gets the total frequency of a lemma.
     * O(1) lookup.
     * 
     * @param lemma The lemma to look up
     * @return The total frequency, or 0 if not found
     */
    public long getFrequency(String lemma) {
        TermStatistics stats = statisticsMap.get(lemma.toLowerCase());
        return stats != null ? stats.totalFrequency() : 0;
    }

    /**
     * Gets the document frequency of a lemma.
     * O(1) lookup.
     * 
     * @param lemma The lemma to look up
     * @return The document frequency, or 0 if not found
     */
    public int getDocumentFrequency(String lemma) {
        TermStatistics stats = statisticsMap.get(lemma.toLowerCase());
        return stats != null ? stats.documentFrequency() : 0;
    }

    /**
     * Gets full statistics for a lemma.
     * O(1) lookup.
     * 
     * @param lemma The lemma to look up
     * @return TermStatistics, or null if not found
     */
    public TermStatistics getStatistics(String lemma) {
        return statisticsMap.get(lemma.toLowerCase());
    }

    /**
     * Checks if a lemma exists in the statistics.
     */
    public boolean hasLemma(String lemma) {
        return statisticsMap.containsKey(lemma.toLowerCase());
    }

    /**
     * Gets all lemmas in the statistics.
     * Used for candidate iteration in span-based queries.
     * 
     * @return Collection of all lemmas (read-only view)
     */
    public java.util.Collection<String> getAllLemmas() {
        return java.util.Collections.unmodifiableSet(statisticsMap.keySet());
    }

    /**
     * Gets all statistics entries, sorted by frequency descending.
     * Useful for iterating candidates by frequency.
     * 
     * @return List of all TermStatistics sorted by frequency
     */
    public java.util.List<TermStatistics> getAllStatisticsByFrequency() {
        return statisticsMap.values().stream()
            .sorted((a, b) -> Long.compare(b.totalFrequency(), a.totalFrequency()))
            .toList();
    }

    /**
     * Gets lemmas filtered by minimum frequency.
     * Useful for pruning rare candidates.
     * 
     * @param minFrequency Minimum total frequency
     * @return List of lemmas with frequency >= minFrequency
     */
    public java.util.List<String> getLemmasByMinFrequency(long minFrequency) {
        return statisticsMap.entrySet().stream()
            .filter(e -> e.getValue().totalFrequency() >= minFrequency)
            .map(java.util.Map.Entry::getKey)
            .toList();
    }

    /**
     * Gets the total number of tokens in the corpus.
     */
    public long getTotalTokens() {
        return totalTokens;
    }

    /**
     * Gets the total number of sentences in the corpus.
     */
    public long getTotalSentences() {
        return totalSentences;
    }

    /**
     * Gets the number of unique lemmas.
     */
    public int getUniqueLemmaCount() {
        return statisticsMap.size();
    }

    /**
     * Gets the estimated memory usage in bytes.
     */
    public long getMemoryUsage() {
        // Rough estimate: 100 bytes per entry (lemma string + TermStatistics object)
        return statisticsMap.size() * 100L;
    }

    private void loadBinaryFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());

            // Read header
            int magic = buffer.getInt();
            if (magic != MAGIC_NUMBER) {
                throw new IOException("Invalid statistics file: bad magic number");
            }

            int version = buffer.getInt();
            if (version != VERSION) {
                throw new IOException("Unsupported statistics file version: " + version);
            }

            totalTokens = buffer.getLong();
            totalSentences = buffer.getLong();
            int entryCount = buffer.getInt();

            // Pre-size the map
            statisticsMap.clear();

            // Read entries
            for (int i = 0; i < entryCount; i++) {
                // Lemma
                int lemmaLen = buffer.getShort() & 0xFFFF;
                byte[] lemmaBytes = new byte[lemmaLen];
                buffer.get(lemmaBytes);
                String lemma = new String(lemmaBytes, StandardCharsets.UTF_8);

                // Frequencies
                long totalFreq = buffer.getLong();
                int docFreq = buffer.getInt();

                // POS distribution
                int posCount = buffer.getShort() & 0xFFFF;
                Map<String, Long> posDist = new HashMap<>(posCount);
                for (int j = 0; j < posCount; j++) {
                    int tagLen = buffer.get() & 0xFF;
                    byte[] tagBytes = new byte[tagLen];
                    buffer.get(tagBytes);
                    String tag = new String(tagBytes, StandardCharsets.UTF_8);
                    long count = buffer.getLong();
                    posDist.put(tag, count);
                }

                statisticsMap.put(lemma, new TermStatistics(lemma, totalFreq, docFreq, posDist));
            }
        }
    }

    private void loadTsvFile(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) {
                    // Parse metadata from comments
                    if (line.contains("Total tokens:")) {
                        totalTokens = Long.parseLong(line.replaceAll("[^0-9]", ""));
                    } else if (line.contains("Total sentences:")) {
                        totalSentences = Long.parseLong(line.replaceAll("[^0-9]", ""));
                    }
                    continue;
                }

                String[] parts = line.split("\t");
                if (parts.length >= 3) {
                    String lemma = parts[0];
                    long totalFreq = Long.parseLong(parts[1]);
                    int docFreq = Integer.parseInt(parts[2]);

                    Map<String, Long> posDist = new HashMap<>();
                    if (parts.length >= 4 && !parts[3].isEmpty()) {
                        for (String posEntry : parts[3].split(",")) {
                            String[] posData = posEntry.split(":");
                            if (posData.length == 2) {
                                posDist.put(posData[0], Long.parseLong(posData[1]));
                            }
                        }
                    }

                    statisticsMap.put(lemma, new TermStatistics(lemma, totalFreq, docFreq, posDist));
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        statisticsMap.clear();
    }

    /**
     * Creates a StatisticsReader from an in-memory StatisticsIndexBuilder.
     * Useful for testing without file I/O.
     */
    public static StatisticsReader fromBuilder(StatisticsIndexBuilder builder) {
        StatisticsReader reader = new StatisticsReader();
        reader.totalTokens = builder.getTotalTokens();
        reader.totalSentences = builder.getTotalSentences();
        
        // This would need access to builder's internal state
        // For now, just return empty reader - actual use would serialize/deserialize
        return reader;
    }

    // Private constructor for fromBuilder
    private StatisticsReader() {
    }
}

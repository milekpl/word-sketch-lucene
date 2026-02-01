package pl.marcinmilkowski.word_sketch.indexer.hybrid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Fast reader for precomputed collocations with O(1) lookup.
 * 
 * Reads collocations.bin built by CollocationsBuilder and provides
 * instant access to top-K collocates for any headword.
 * 
 * Thread-safe for concurrent queries.
 */
public class CollocationsReader implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(CollocationsReader.class);

    private static final int MAGIC_NUMBER = 0x434F4C4C; // "COLL"
    private static final int EXPECTED_VERSION = 1;

    private final MappedByteBuffer buffer;
    private final Map<String, Long> offsetIndex;
    
    // Metadata from header
    private final int entryCount;
    private final int windowSize;
    private final int topK;
    private final long totalCorpusTokens;

    /**
     * Open collocations file.
     * 
     * @param path Path to collocations.bin
     */
    public CollocationsReader(String path) throws IOException {
        if (!Files.exists(Paths.get(path))) {
            throw new FileNotFoundException("Collocations file not found: " + path);
        }

        try (FileChannel channel = FileChannel.open(Paths.get(path), StandardOpenOption.READ)) {
            // Memory-map the entire file for fast random access
            this.buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());

            // Read header
            int magic = buffer.getInt();
            if (magic != MAGIC_NUMBER) {
                throw new IOException("Invalid collocations file: bad magic number");
            }

            int version = buffer.getInt();
            if (version != EXPECTED_VERSION) {
                throw new IOException("Unsupported collocations file version: " + version);
            }

            this.entryCount = buffer.getInt();
            this.windowSize = buffer.getInt();
            this.topK = buffer.getInt();
            this.totalCorpusTokens = buffer.getLong();

            long offsetTableOffset = buffer.getLong();
            long offsetTableSize = buffer.getLong();

            // Read offset index
            buffer.position((int) offsetTableOffset);
            this.offsetIndex = readOffsetTable();

            logger.info("Loaded collocations from {}", path);
            logger.info("  Entries: {}", entryCount);
            logger.info("  Window size: {}", windowSize);
            logger.info("  Top-K: {}", topK);
            logger.info("  Memory usage: {} MB", channel.size() / (1024 * 1024));
        }
    }

    /**
     * Get collocations for a headword.
     * O(1) lookup + O(K) deserialization where K = topK.
     * 
     * @param headword The headword lemma (case-insensitive)
     * @return CollocationEntry or null if not found
     */
    public CollocationEntry getCollocations(String headword) {
        if (headword == null) return null;

        String normalized = headword.toLowerCase();
        Long offset = offsetIndex.get(normalized);

        if (offset == null) {
            return null;
        }

        // Position buffer and read entry
        buffer.position(offset.intValue());
        return readEntry();
    }

    /**
     * Check if a headword exists in the index.
     */
    public boolean hasLemma(String headword) {
        return offsetIndex.containsKey(headword.toLowerCase());
    }

    /**
     * Get the window size used during building.
     */
    public int getWindowSize() {
        return windowSize;
    }

    /**
     * Get the number of entries.
     */
    public int getEntryCount() {
        return entryCount;
    }

    /**
     * Get the top-K limit.
     */
    public int getTopK() {
        return topK;
    }

    /**
     * Get total corpus size.
     */
    public long getTotalCorpusTokens() {
        return totalCorpusTokens;
    }

    /**
     * Get all available headwords.
     */
    public Set<String> getAllHeadwords() {
        return Collections.unmodifiableSet(offsetIndex.keySet());
    }

    /**
     * Read offset table from buffer.
     */
    private Map<String, Long> readOffsetTable() {
        int count = buffer.getInt();
        Map<String, Long> map = new HashMap<>(count);

        for (int i = 0; i < count; i++) {
            short lemmaLen = buffer.getShort();
            byte[] lemmaBytes = new byte[lemmaLen];
            buffer.get(lemmaBytes);
            String lemma = new String(lemmaBytes, StandardCharsets.UTF_8);

            long offset = buffer.getLong();
            map.put(lemma, offset);
        }

        return map;
    }

    /**
     * Read a single entry from current buffer position.
     */
    private CollocationEntry readEntry() {
        // Read headword
        short headwordLen = buffer.getShort();
        byte[] headwordBytes = new byte[headwordLen];
        buffer.get(headwordBytes);
        String headword = new String(headwordBytes, StandardCharsets.UTF_8);

        // Read frequency
        long headwordFreq = buffer.getLong();

        // Read collocates
        short collocateCount = buffer.getShort();
        List<Collocation> collocates = new ArrayList<>(collocateCount);

        for (int i = 0; i < collocateCount; i++) {
            byte lemmaLen = buffer.get();
            byte[] lemmaBytes = new byte[lemmaLen & 0xFF];
            buffer.get(lemmaBytes);
            String lemma = new String(lemmaBytes, StandardCharsets.UTF_8);

            byte posLen = buffer.get();
            byte[] posBytes = new byte[posLen & 0xFF];
            buffer.get(posBytes);
            String pos = new String(posBytes, StandardCharsets.UTF_8);

            long cooccurrence = buffer.getLong();
            long frequency = buffer.getLong();
            float logDice = buffer.getFloat();

            collocates.add(new Collocation(lemma, pos, cooccurrence, frequency, logDice));
        }

        return new CollocationEntry(headword, headwordFreq, collocates);
    }

    @Override
    public void close() {
        // Memory-mapped buffers are automatically cleaned up by GC
        offsetIndex.clear();
        logger.info("CollocationsReader closed");
    }

    /**
     * Get memory usage estimate.
     */
    public long getMemoryUsage() {
        return buffer.capacity() + (offsetIndex.size() * 32L);
    }
}

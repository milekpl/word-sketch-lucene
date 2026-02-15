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
 * Fast reader for relation-specific precomputed collocations with O(1) lookup.
 *
 * Reads relation_collocations.bin built by RelationCollocationsBuilder and provides
 * instant access to top-K collocates for any (headword, relation) pair.
 *
 * Thread-safe for concurrent queries.
 */
public class RelationCollocationsReader implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(RelationCollocationsReader.class);

    private static final int MAGIC = 0x524C434C; // "RLCL"
    private static final int EXPECTED_VERSION = 1;

    private final MappedByteBuffer buffer;
    private final Map<String, Long> offsetIndex;

    // Metadata from header
    private final int entryCount;
    private final int relationsCount;
    private final UUID corpusUuid;
    private final long buildTimestamp;
    private final int windowSize;
    private final int topK;
    private final List<String> relationIds;

    /**
     * Open relation collocations file.
     *
     * @param path Path to relation_collocations.bin
     */
    public RelationCollocationsReader(String path) throws IOException {
        if (!Files.exists(Paths.get(path))) {
            throw new FileNotFoundException("Relation collocations file not found: " + path);
        }

        try (FileChannel channel = FileChannel.open(Paths.get(path), StandardOpenOption.READ)) {
            // Memory-map the entire file for fast random access
            this.buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());

            // Read and validate header
            int magic = buffer.getInt();
            if (magic != MAGIC) {
                throw new IOException("Invalid relation collocations file: bad magic number");
            }

            int version = buffer.getInt();
            if (version != EXPECTED_VERSION) {
                throw new IOException("Unsupported relation collocations file version: " + version);
            }

            this.entryCount = buffer.getInt();
            this.relationsCount = buffer.getInt();

            // Read corpus UUID (16 bytes)
            byte[] uuidBytes = new byte[16];
            buffer.get(uuidBytes);
            this.corpusUuid = uuidFromBytes(uuidBytes);

            this.buildTimestamp = buffer.getLong();
            this.windowSize = buffer.getInt();
            this.topK = buffer.getInt();
            long offsetTableOffset = buffer.getLong();

            // Skip extra fields in header (written by CollocationsBuilder)
            // offsetTableSize: 8 bytes, relationsOffset: 8 bytes, plus reserved space (64 bytes)
            buffer.position(192); // Header (128) + reserved (64) = 192

            // Read relations section
            this.relationIds = readRelations();

            // Read offset table
            buffer.position((int) offsetTableOffset);
            this.offsetIndex = readOffsetTable();

            logger.info("Loaded relation collocations from {}", path);
            logger.info("  Entries: {}", entryCount);
            logger.info("  Relations: {}", relationsCount);
            logger.info("  Corpus UUID: {}", corpusUuid);
            logger.info("  Window size: {}", windowSize);
            logger.info("  Top-K: {}", topK);
            logger.info("  Memory usage: {} MB", channel.size() / (1024 * 1024));
        }
    }

    /**
     * Get collocations for a (headword, relation) pair.
     * O(1) lookup + O(K) deserialization where K = number of collocations.
     *
     * @param headword The headword lemma (case-insensitive)
     * @param relationId The relation identifier
     * @return RelationCollocations or null if not found
     */
    public RelationCollocations getCollocations(String headword, String relationId) {
        if (headword == null || relationId == null) {
            return null;
        }

        String key = buildKey(headword.toLowerCase(), relationId);
        Long offset = offsetIndex.get(key);

        if (offset == null) {
            return null;
        }

        // Position buffer and read entry
        buffer.position(offset.intValue());
        return readEntry();
    }

    /**
     * Check if a specific (headword, relation) pair has data.
     */
    public boolean hasRelationData(String headword, String relationId) {
        if (headword == null || relationId == null) {
            return false;
        }
        String key = buildKey(headword.toLowerCase(), relationId);
        return offsetIndex.containsKey(key);
    }

    /**
     * Check if a headword has any relation data.
     */
    public boolean hasHeadword(String headword) {
        if (headword == null) {
            return false;
        }
        String normalized = headword.toLowerCase();
        return offsetIndex.keySet().stream().anyMatch(key -> key.startsWith(normalized + "|"));
    }

    /**
     * Get corpus UUID for integrity checking.
     */
    public UUID getCorpusUuid() {
        return corpusUuid;
    }

    /**
     * Get build timestamp.
     */
    public long getBuildTimestamp() {
        return buildTimestamp;
    }

    /**
     * Get window size used during building.
     */
    public int getWindowSize() {
        return windowSize;
    }

    /**
     * Get the top-K limit.
     */
    public int getTopK() {
        return topK;
    }

    /**
     * Get number of entries.
     */
    public int getEntryCount() {
        return entryCount;
    }

    /**
     * Get number of relations.
     */
    public int getRelationsCount() {
        return relationsCount;
    }

    /**
     * Get all available relation IDs.
     */
    public List<String> getRelationIds() {
        return Collections.unmodifiableList(relationIds);
    }

    /**
     * Get all available keys (headword|relationId).
     */
    public Set<String> getAllKeys() {
        return Collections.unmodifiableSet(offsetIndex.keySet());
    }

    /**
     * Build composite key from headword and relation ID.
     */
    static String buildKey(String headword, String relationId) {
        return headword + "|" + relationId;
    }

    /**
     * Parse composite key into headword and relation ID.
     */
    static String[] parseKey(String key) {
        int sep = key.indexOf('|');
        if (sep < 0) {
            throw new IllegalArgumentException("Invalid composite key: " + key);
        }
        return new String[]{key.substring(0, sep), key.substring(sep + 1)};
    }

    /**
     * Read relations section.
     */
    private List<String> readRelations() {
        List<String> relations = new ArrayList<>(relationsCount);
        for (int i = 0; i < relationsCount; i++) {
            short len = buffer.getShort();
            byte[] bytes = new byte[len];
            buffer.get(bytes);
            relations.add(new String(bytes, StandardCharsets.UTF_8));
        }
        return relations;
    }

    /**
     * Read offset table from buffer.
     * Format per entry: 2-byte key length + key bytes + 8-byte offset
     */
    private Map<String, Long> readOffsetTable() {
        int count = buffer.getInt();
        Map<String, Long> map = new HashMap<>(count);

        for (int i = 0; i < count; i++) {
            short keyLen = buffer.getShort();
            byte[] keyBytes = new byte[keyLen];
            buffer.get(keyBytes);
            String key = new String(keyBytes, StandardCharsets.UTF_8);

            long offset = buffer.getLong();
            map.put(key, offset);
        }

        return map;
    }

    /**
     * Read a single entry from current buffer position.
     */
    private RelationCollocations readEntry() {
        // Read key length and key (lemma|relationId)
        short keyLen = buffer.getShort();
        byte[] keyBytes = new byte[keyLen];
        buffer.get(keyBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);

        String[] parts = parseKey(key);
        String headword = parts[0];
        String relationId = parts[1];

        // Read headword frequency
        long headwordFreq = buffer.getLong();

        // Read collocations
        short collocateCount = buffer.getShort();
        List<RelationCollocation> collocates = new ArrayList<>(collocateCount);

        for (int i = 0; i < collocateCount; i++) {
            // Collocate lemma
            short lemmaLen = buffer.getShort();
            byte[] lemmaBytes = new byte[lemmaLen];
            buffer.get(lemmaBytes);
            String collocate = new String(lemmaBytes, StandardCharsets.UTF_8);

            // POS tag
            short posLen = buffer.getShort();
            byte[] posBytes = new byte[posLen];
            buffer.get(posBytes);
            String pos = new String(posBytes, StandardCharsets.UTF_8);

            // Cooccurrence count and logDice
            long cooccurrence = buffer.getLong();
            float logDice = buffer.getFloat();

            collocates.add(new RelationCollocation(collocate, pos, cooccurrence, logDice));
        }

        return new RelationCollocations(headword, relationId, headwordFreq, collocates);
    }

    /**
     * Convert 16-byte array to UUID.
     */
    private static UUID uuidFromBytes(byte[] bytes) {
        if (bytes.length != 16) {
            throw new IllegalArgumentException("UUID requires 16 bytes");
        }
        long mostSig = 0;
        long leastSig = 0;
        for (int i = 0; i < 8; i++) {
            mostSig = (mostSig << 8) | (bytes[i] & 0xFF);
        }
        for (int i = 8; i < 16; i++) {
            leastSig = (leastSig << 8) | (bytes[i] & 0xFF);
        }
        return new UUID(mostSig, leastSig);
    }

    /**
     * Convert UUID to 16-byte array.
     */
    static byte[] uuidToBytes(UUID uuid) {
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

    @Override
    public void close() {
        offsetIndex.clear();
        logger.info("RelationCollocationsReader closed");
    }

    /**
     * Get memory usage estimate.
     */
    public long getMemoryUsage() {
        return buffer.capacity() + (offsetIndex.size() * 32L);
    }

    /**
     * A collection of collocations for a specific (headword, relation) pair.
     */
    public record RelationCollocations(
        String headword,
        String relationId,
        long headwordFrequency,
        List<RelationCollocation> collocations
    ) {
        /**
         * Get collocations sorted by logDice descending.
         */
        public List<RelationCollocation> getTopCollocations(int limit) {
            return collocations.stream()
                .sorted()
                .limit(limit)
                .toList();
        }

        /**
         * Get collocations filtered by minimum logDice.
         */
        public List<RelationCollocation> getCollocationsAbove(double minLogDice) {
            return collocations.stream()
                .filter(c -> c.logDice() >= minLogDice)
                .sorted()
                .toList();
        }

        /**
         * Get collocations filtered by POS tag.
         */
        public List<RelationCollocation> getCollocationsWithPos(String posPrefix) {
            return collocations.stream()
                .filter(c -> c.pos().startsWith(posPrefix))
                .sorted()
                .toList();
        }
    }

    /**
     * A single relation-specific collocation.
     */
    public record RelationCollocation(
        String collocate,
        String pos,
        long cooccurrence,
        float logDice
    ) implements Comparable<RelationCollocation> {

        @Override
        public int compareTo(RelationCollocation other) {
            return Float.compare(other.logDice, this.logDice);
        }

        @Override
        public String toString() {
            return String.format("%s (%s) logDice=%.2f cooc=%d",
                collocate, pos, logDice, cooccurrence);
        }
    }
}

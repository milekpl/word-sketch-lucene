package pl.marcinmilkowski.word_sketch.query;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Factory for creating QueryExecutor instances.
 * 
 * Supports different index types:
 * - LEGACY: Token-per-document index
 * - HYBRID: Sentence-per-document index
 * - DUAL: Runs both and compares results (for verification)
 */
public class QueryExecutorFactory {

    /**
     * Index type enumeration.
     */
    public enum IndexType {
        /** Token-per-document index (legacy) */
        LEGACY,
        
        /** Sentence-per-document index (hybrid) */
        HYBRID,
        
        /** Dual mode: runs both implementations and compares */
        DUAL
    }

    /**
     * Create a QueryExecutor for the given index path and type.
     * 
     * @param indexPath Path to the Lucene index directory
     * @param type Type of executor to create
     * @return QueryExecutor instance
     * @throws IOException if index cannot be opened
     * @throws UnsupportedOperationException if type is not yet implemented
     */
    public static QueryExecutor create(String indexPath, IndexType type) throws IOException {
        return switch (type) {
            case LEGACY -> new WordSketchQueryExecutor(indexPath);
            case HYBRID -> new HybridQueryExecutor(indexPath);
            case DUAL -> throw new UnsupportedOperationException(
                "Dual mode executor not yet implemented");
        };
    }

    /**
     * Create a legacy QueryExecutor (convenience method).
     * 
     * @param indexPath Path to the Lucene index directory
     * @return QueryExecutor instance
     * @throws IOException if index cannot be opened
     */
    public static QueryExecutor createLegacy(String indexPath) throws IOException {
        return create(indexPath, IndexType.LEGACY);
    }

    /**
     * Create a hybrid QueryExecutor (convenience method).
     * 
     * @param indexPath Path to the Lucene index directory
     * @return QueryExecutor instance
     * @throws IOException if index cannot be opened
     */
    public static QueryExecutor createHybrid(String indexPath) throws IOException {
        return create(indexPath, IndexType.HYBRID);
    }

    /**
     * Detect the index type from the index directory.
     * 
     * Checks for the presence of "sentence_id" field to identify hybrid indexes.
     * 
     * @param indexPath Path to the Lucene index directory
     * @return Detected index type
     */
    public static IndexType detectIndexType(String indexPath) {
        try {
            Path path = Paths.get(indexPath);
            try (Directory dir = MMapDirectory.open(path);
                 IndexReader reader = DirectoryReader.open(dir)) {
                
                // Check if index has the hybrid schema field by checking field infos
                if (!reader.leaves().isEmpty()) {
                    var fieldInfos = reader.leaves().get(0).reader().getFieldInfos();
                    // Hybrid index has IntPoint field "sentence_id", legacy has IntPoint field "doc_id"
                    if (fieldInfos.fieldInfo("sentence_id") != null) {
                        System.out.println("Detected HYBRID index at: " + indexPath);
                        return IndexType.HYBRID;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error detecting index type: " + e.getMessage());
        }
        
        System.out.println("Detected LEGACY index at: " + indexPath);
        return IndexType.LEGACY;
    }

    /**
     * Create a QueryExecutor with auto-detection and optional collocations file
     * 
     * @param indexPath Path to the Lucene index directory
     * @param collocationPath Optional path to collocations.bin (can be null)
     * @return QueryExecutor instance for the detected index type
     * @throws IOException if index cannot be opened
     */
    public static QueryExecutor createAutoDetect(String indexPath, String collocationPath) throws IOException {
        IndexType type = detectIndexType(indexPath);
        QueryExecutor executor = create(indexPath, type);
        
        if (collocationPath != null && executor instanceof HybridQueryExecutor) {
            ((HybridQueryExecutor) executor).setCollocationPath(collocationPath);
        }
        
        return executor;
    }

    /**
     * Create a QueryExecutor with auto-detection
     * 
     * @param indexPath Path to the Lucene index directory
     * @return QueryExecutor instance for the detected index type
     * @throws IOException if index cannot be opened
     */
    public static QueryExecutor createAutoDetect(String indexPath) throws IOException {
        return createAutoDetect(indexPath, null);
    }
}

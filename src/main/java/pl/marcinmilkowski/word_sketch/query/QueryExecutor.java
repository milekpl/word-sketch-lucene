package pl.marcinmilkowski.word_sketch.query;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * Interface for word sketch query executors.
 * Allows different implementations (legacy, hybrid, BlackLab) to be used interchangeably.
 */
public interface QueryExecutor extends Closeable {

    /**
     * Find collocations for a headword using a CQL pattern.
     *
     * @param cqlPattern  CQL pattern defining the collocate constraints
     * @param minLogDice  Minimum logDice score (0 for no minimum)
     * @return List of collocation results, sorted by logDice descending
     * @throws IOException if index access fails
     */
    List<QueryResults.WordSketchResult> findCollocations(String headword, String cqlPattern,
                                             double minLogDice, int maxResults) throws IOException;

    /**
     * Execute a general CQL query and return concordance results.
     */
    List<QueryResults.ConcordanceResult> executeQuery(String cqlPattern, int maxResults) throws IOException;

    /**
     * Execute a BCQL (BlackLab Corpus Query Language) query for concordance results.
     * Uses CorpusQueryLanguageParser instead of ContextualQueryLanguageParser.
     */
    default List<QueryResults.ConcordanceResult> executeBcqlQuery(String bcqlPattern, int maxResults) throws IOException {
        // Default: treat BCQL as CQL (for backward compatibility)
        return executeQuery(bcqlPattern, maxResults);
    }

    /**
     * Get the total frequency of a lemma in the corpus.
     */
    long getTotalFrequency(String lemma) throws IOException;

    /**
     * Execute a surface pattern query for word sketches using labeled BCQL capture groups.
     *
     * @param bcqlPattern       BCQL pattern with labeled positions (1: head, 2: collocate)
     * @param headPosition      Position index of the head token in the pattern
     * @param collocatePosition Position index of the collocate token in the pattern
     * @param minLogDice        Minimum logDice score (0 for no minimum)
     */
    default List<QueryResults.WordSketchResult> executeSurfacePattern(
            String lemma, String bcqlPattern,
            int headPosition, int collocatePosition,
            double minLogDice, int maxResults) throws IOException {
        // Default: fall back to findCollocations (ignores position hints)
        return findCollocations(lemma, bcqlPattern, minLogDice, maxResults);
    }

    /**
     * Get the type of this executor for logging/debugging.
     *
     * @return Executor type name (e.g., "legacy", "hybrid")
     */
    default String getExecutorType() {
        return this.getClass().getSimpleName();
    }

    default boolean isReady() {
        return true;
    }
}

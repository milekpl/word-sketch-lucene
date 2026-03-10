package pl.marcinmilkowski.word_sketch.query;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * Interface for word sketch query executors.
 * Allows different implementations to be used interchangeably.
 *
 * <h2>Method responsibilities</h2>
 * <ul>
 *   <li>{@link #findCollocations} — corpus-frequency collocate lookup for a headword
 *       via a plain CQL pattern (no labeled positions). Returns collocates ranked by logDice.</li>
 *   <li>{@link #executeQuery} — general concordance retrieval using CQL syntax
 *       (ContextualQueryLanguageParser). Returns raw KWIC results without ranking.</li>
 *   <li>{@link #executeBcqlQuery} — concordance retrieval using BCQL syntax
 *       (CorpusQueryLanguageParser). Handles labeled capture groups and computes
 *       per-hit logDice scores. Distinct from {@link #executeQuery}.</li>
 *   <li>{@link #executeSurfacePattern} — word-sketch collocate extraction using a labeled
 *       BCQL pattern ({@code 1:} for head, {@code 2:} for collocate). Uses explicit
 *       position hints to identify the head and collocate tokens; returns results ranked
 *       by logDice. Distinct from {@link #findCollocations} which ignores position hints.</li>
 * </ul>
 */
public interface QueryExecutor extends Closeable {

    /**
     * Find collocations for a lemma using a CQL pattern.
     * Groups hits by collocate identity and ranks results by logDice.
     *
     * @param lemma       The head lemma to search for
     * @param cqlPattern  CQL pattern defining the collocate constraints (no labeled positions)
     * @param minLogDice  Minimum logDice score threshold (0 for no minimum)
     * @param maxResults  Maximum number of results to return
     * @return List of collocation results, sorted by logDice descending
     * @throws IOException if index access fails
     */
    List<QueryResults.WordSketchResult> findCollocations(String lemma, String cqlPattern,
                                             double minLogDice, int maxResults) throws IOException;

    /**
     * Execute a general CQL query (ContextualQueryLanguageParser) and return raw concordance results.
     * Does not compute logDice or extract collocates — use {@link #executeBcqlQuery} for that.
     *
     * @param cqlPattern  CQL pattern string
     * @param maxResults  Maximum number of concordance results
     * @return Concordance results in hit order
     * @throws IOException if index access fails
     */
    List<QueryResults.ConcordanceResult> executeQuery(String cqlPattern, int maxResults) throws IOException;

    /**
     * Execute a BCQL (CorpusQueryLanguageParser) pattern for concordance results.
     * Supports labeled capture groups ({@code 1:}, {@code 2:}) and computes per-hit logDice scores.
     * Unlike {@link #executeQuery}, this uses the BCQL parser and ranks results by logDice.
     *
     * @param bcqlPattern  BCQL pattern, optionally with labeled positions
     * @param maxResults   Maximum number of results after ranking
     * @throws IOException if index access or parsing fails
     */
    List<QueryResults.ConcordanceResult> executeBcqlQuery(String bcqlPattern, int maxResults) throws IOException;

    /**
     * Get the total frequency of a lemma in the corpus.
     *
     * @param lemma  The lemma to look up
     * @return Total occurrence count, or 0 if not found
     * @throws IOException if index access fails
     */
    long getTotalFrequency(String lemma) throws IOException;

    /**
     * Execute a surface pattern query for word sketches using a labeled BCQL pattern.
     * The head token is at {@code headPosition} and the collocate at {@code collocatePosition}
     * (both 1-based). Unlike {@link #findCollocations}, position hints are used to correctly
     * extract the collocate from multi-token patterns.
     *
     * @param lemma             The head lemma (already substituted into {@code bcqlPattern})
     * @param bcqlPattern       BCQL pattern with labeled positions (1: head, 2: collocate)
     * @param headPosition      1-based position of the head token in the pattern
     * @param collocatePosition 1-based position of the collocate token in the pattern
     * @param minLogDice        Minimum logDice score threshold (0 for no minimum)
     * @param maxResults        Maximum number of results to return
     * @return Collocate results ranked by logDice descending
     * @throws IOException if index access or parsing fails
     */
    List<QueryResults.WordSketchResult> executeSurfacePattern(
            String lemma, String bcqlPattern,
            int headPosition, int collocatePosition,
            double minLogDice, int maxResults) throws IOException;

    /**
     * Execute a dependency-pattern query for word sketches with an optional head POS constraint.
     *
     * @param lemma              The head lemma to search for
     * @param deprel             The dependency relation label (e.g., "nsubj", "obj")
     * @param headPosConstraint  Optional POS regex for the head token (may be null)
     * @param minLogDice         Minimum logDice score threshold (0 for no minimum)
     * @param maxResults         Maximum number of results to return
     * @return Collocate results ranked by logDice descending
     * @throws IOException if index access or parsing fails
     */
    List<QueryResults.WordSketchResult> executeDependencyPattern(
            String lemma, String deprel, String headPosConstraint,
            double minLogDice, int maxResults) throws IOException;

    /**
     * Get the type of this executor for logging/debugging.
     *
     * @return Executor type name (e.g., "blacklab")
     */
    default String getExecutorType() {
        return this.getClass().getSimpleName();
    }

}

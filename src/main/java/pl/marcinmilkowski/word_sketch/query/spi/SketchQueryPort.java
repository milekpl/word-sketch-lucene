package pl.marcinmilkowski.word_sketch.query.spi;

import java.io.IOException;
import java.util.List;

import org.jspecify.annotations.NonNull;
import pl.marcinmilkowski.word_sketch.model.sketch.WordSketchResult;

/**
 * Narrow query port for word-sketch lookups (both surface-pattern and dependency-pattern).
 *
 * <p>{@link pl.marcinmilkowski.word_sketch.query.QueryExecutor} extends this interface, so any
 * {@code QueryExecutor} implementation is automatically a {@code SketchQueryPort}. Exploration
 * components that only need collocate extraction (e.g.
 * {@link pl.marcinmilkowski.word_sketch.exploration.SingleSeedExplorer},
 * {@link pl.marcinmilkowski.word_sketch.exploration.MultiSeedExplorer}, and
 * {@link pl.marcinmilkowski.word_sketch.exploration.CollocateProfileComparator}) should declare
 * this narrower type to make their dependency surface explicit.</p>
 */
public interface SketchQueryPort {

    /**
     * Execute a surface pattern query for word sketches using a labeled BCQL pattern.
     *
     * @param bcqlPattern  BCQL pattern with labeled positions (1: head, 2: collocate) and
     *                     an embedded {@code lemma="..."} attribute for the headword
     * @param minLogDice   Minimum logDice score threshold (0 for no minimum)
     * @param maxResults   Maximum number of results to return
     * @return Collocate results ranked by logDice descending
     * @throws IOException if index access or parsing fails
     * @throws IllegalArgumentException if the headword lemma cannot be extracted from {@code bcqlPattern}
     */
    @NonNull List<WordSketchResult> executeSurfaceCollocations(
            @NonNull String bcqlPattern,
            double minLogDice, int maxResults) throws IOException;

    /**
     * Execute a dependency-annotation or precomputed collocation query for word sketches.
     *
     * @param lemma        the headword lemma; used to look up corpus frequency for logDice
     * @param cqlPattern   CQL pattern string (dependency or BCQL variant)
     * @param minLogDice   Minimum logDice score threshold (0 for no minimum)
     * @param maxResults   Maximum number of results to return
     * @return Collocate results ranked by logDice descending
     * @throws IOException if index access fails
     * @throws IllegalArgumentException if {@code lemma} is null or empty, or {@code cqlPattern} is not recognized
     */
    @NonNull List<WordSketchResult> executeCollocations(
            @NonNull String lemma, @NonNull String cqlPattern,
            double minLogDice, int maxResults) throws IOException;
}

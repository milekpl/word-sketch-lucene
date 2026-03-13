package pl.marcinmilkowski.word_sketch.query;

import java.io.IOException;
import java.util.List;

import org.jspecify.annotations.NonNull;
import pl.marcinmilkowski.word_sketch.model.sketch.WordSketchResult;

/**
 * Narrow query port for word-sketch surface-pattern lookups.
 *
 * <p>{@link QueryExecutor} extends this interface, so any {@code QueryExecutor} implementation is
 * automatically a {@code SketchQueryPort}. Handlers that only need surface-pattern collocate
 * extraction (e.g. {@link pl.marcinmilkowski.word_sketch.api.SketchHandlers}) should declare
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
     */
    @NonNull List<WordSketchResult> executeSurfacePattern(
            @NonNull String bcqlPattern,
            double minLogDice, int maxResults) throws IOException;
}

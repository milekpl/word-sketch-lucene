package pl.marcinmilkowski.word_sketch.query;

import java.io.IOException;
import java.util.List;

import org.jspecify.annotations.NonNull;
import pl.marcinmilkowski.word_sketch.model.sketch.*;

/**
 * Narrow query port covering corpus-text lookups that return {@link CollocateResult}
 * instances — i.e. concordance and BCQL pattern queries.
 *
 * <p>{@link QueryExecutor} extends this interface, so any {@code QueryExecutor} implementation is
 * automatically a {@code CollocateQueryPort}. Handlers that only need BCQL retrieval (e.g.
 * {@link pl.marcinmilkowski.word_sketch.api.ConcordanceHandlers} and
 * {@link pl.marcinmilkowski.word_sketch.api.CorpusQueryHandlers}) should declare this narrower
 * type to make their dependency surface explicit.</p>
 */
public interface CollocateQueryPort {

    /**
     * Execute a BCQL pattern and return concordance results.
     * Supports labeled capture groups ({@code 1:}, {@code 2:}) and computes per-hit logDice scores.
     *
     * @implNote Uses the BCQL parser ({@code CorpusQueryLanguageParser}). When scoring is required,
     *           the headword lemma must be embedded in {@code bcqlPattern} (e.g. via a
     *           {@code lemma="..."} attribute on the head token). This is intentionally asymmetric
     *           with {@link QueryExecutor#executeCollocations}, which accepts the lemma as a
     *           separate parameter and looks up its frequency independently.
     *
     * @param bcqlPattern  BCQL pattern, optionally with labeled positions; embed the headword
     *                     lemma in the pattern when per-hit logDice scoring is needed
     * @param maxResults   Maximum number of results after ranking
     * @return Concordance results, ranked by logDice
     * @throws IOException if index access or parsing fails
     */
    @NonNull List<CollocateResult> executeBcqlQuery(@NonNull String bcqlPattern, int maxResults) throws IOException;
}

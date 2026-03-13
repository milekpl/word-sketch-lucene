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
     * Execute a BCQL (CorpusQueryLanguageParser) pattern and return concordance results.
     * Supports labeled capture groups ({@code 1:}, {@code 2:}) and computes per-hit logDice scores.
     *
     * @param bcqlPattern  BCQL pattern, optionally with labeled positions
     * @param maxResults   Maximum number of results after ranking
     * @return Concordance results, ranked by logDice
     * @throws IOException if index access or parsing fails
     */
    @NonNull List<CollocateResult> executeBcqlQuery(@NonNull String bcqlPattern, int maxResults) throws IOException;
}

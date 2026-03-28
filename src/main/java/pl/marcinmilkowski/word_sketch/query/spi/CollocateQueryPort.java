package pl.marcinmilkowski.word_sketch.query.spi;

import java.io.IOException;
import java.util.List;

import org.jspecify.annotations.NonNull;
import pl.marcinmilkowski.word_sketch.model.sketch.*;

/**
 * Narrow query port covering corpus-text lookups that return {@link CollocateResult}
 * instances — i.e. concordance and BCQL pattern queries.
 *
 * <p>{@link pl.marcinmilkowski.word_sketch.query.QueryExecutor} extends this interface, so any
 * {@code QueryExecutor} implementation is automatically a {@code CollocateQueryPort}. Handlers
 * that only need BCQL retrieval (e.g.
 * {@link pl.marcinmilkowski.word_sketch.api.ConcordanceHandlers} and
 * {@link pl.marcinmilkowski.word_sketch.api.CorpusQueryHandlers}) should declare this narrower
 * type to make their dependency surface explicit.</p>
 */
public interface CollocateQueryPort {

    /**
     * Execute a BCQL pattern and return concordance results in document order.
     * No logDice scoring or ranking is performed.
     *
     * <p>Labeled capture groups ({@code 1:}, {@code 2:}) are supported for collocate-lemma
     * extraction per hit but do not affect result ordering.</p>
     *
     * @param bcqlPattern  BCQL pattern to match
     * @param maxResults   maximum number of results to return (positive)
     * @return Concordance results starting at offset 0
     * @throws IOException if index access or parsing fails
     */
    @NonNull List<CollocateResult> executeBcqlQuery(@NonNull String bcqlPattern, int maxResults) throws IOException;

    /**
     * Paginated BCQL concordance query. Returns one page of results together with the total
     * hit count, enabling clients to iterate pages without re-executing a full scan.
     * Hits are returned in document order; no logDice scoring is performed.
     *
     * <p>The default implementation delegates to {@link #executeBcqlQuery} with
     * {@code pageSize} as the limit and synthesises a {@link BcqlPage} where {@code total}
     * equals the number of results returned — suitable for stubs and tests.</p>
     *
     * @param bcqlPattern  BCQL pattern to match
     * @param pageSize     number of hits to fetch for this page (positive)
     * @param offset       0-based index of the first hit on this page
     * @return {@link BcqlPage} with total hit count, offset, page size, and results
     * @throws IOException if index access or parsing fails
     */
    default @NonNull BcqlPage executeBcqlPage(@NonNull String bcqlPattern, int pageSize, int offset) throws IOException {
        List<CollocateResult> results = executeBcqlQuery(bcqlPattern, pageSize);
        return new BcqlPage(results.size(), offset, pageSize, results);
    }
}

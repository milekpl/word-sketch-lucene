package pl.marcinmilkowski.word_sketch.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.StubQueryExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation tests for {@link ConcordanceHandlers} — missing required query parameters.
 */
class ConcordanceHandlersTest {

    private static QueryExecutor stubExecutor() {
        return new StubQueryExecutor() {
            @Override
            public List<QueryResults.CollocateResult> executeBcqlQuery(String bcqlPattern, int maxResults) {
                return List.of(new QueryResults.CollocateResult("The big house", null, 4, 7, "d1", "big", 1, 7.5));
            }
        };
    }

    @Test
    void handleConcordanceExamples_validParams_returns200() throws Exception {
        ConcordanceHandlers handlers = new ConcordanceHandlers(stubExecutor(), GrammarConfigHelper.requireTestConfig());
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/concordance/examples?seed=house&collocate=big&relation=noun_adj_predicates");
        handlers.handleConcordanceExamples(ex);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.MAPPER.readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
        assertEquals("house", body.path("seed").asText());
        assertEquals("big", body.path("collocate").asText());
        assertTrue(body.has("examples"), "Response must contain 'examples' key");
    }

    @Test
    void handleConcordanceExamples_missingWord1_returns400() throws Exception {
        ConcordanceHandlers handlers = new ConcordanceHandlers(null, GrammarConfigHelper.requireTestConfig());
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/concordance?collocate=house");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleConcordanceExamples, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleConcordanceExamples_missingWord2_returns400() throws Exception {
        ConcordanceHandlers handlers = new ConcordanceHandlers(null, GrammarConfigHelper.requireTestConfig());
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/concordance?seed=big");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleConcordanceExamples, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleConcordanceExamples_missingBothWords_returns400() throws Exception {
        ConcordanceHandlers handlers = new ConcordanceHandlers(null, GrammarConfigHelper.requireTestConfig());
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/concordance");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleConcordanceExamples, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }
}

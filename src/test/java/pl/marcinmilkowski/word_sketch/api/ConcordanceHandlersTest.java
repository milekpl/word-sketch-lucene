package pl.marcinmilkowski.word_sketch.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.model.sketch.*;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.StubQueryExecutor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation tests for {@link ConcordanceHandlers} — missing required query parameters.
 */
class ConcordanceHandlersTest {

    private static QueryExecutor stubExecutor() {
        return new StubQueryExecutor() {
            @Override
            public List<CollocateResult> executeBcqlQuery(String bcqlPattern, int maxResults) {
                return List.of(new CollocateResult("The big house", null, 4, 7, "d1", "big", 1, 7.5));
            }
        };
    }

    /**
     * Verifies that {@link ConcordanceHandlers#handleConcordanceExamples} invokes
     * {@code executeBcqlQuery} exactly once per request — confirming that
     * {@code RelationPatternUtils.buildFullPattern} is not called twice with identical args.
     * The response {@code bcql} field must also match the pattern passed to the executor.
     */
    @Test
    void handleConcordanceExamples_executorCalledExactlyOnce() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        String[] capturedPattern = new String[1];
        QueryExecutor countingExecutor = new StubQueryExecutor() {
            @Override
            public List<CollocateResult> executeBcqlQuery(String bcqlPattern, int maxResults) {
                callCount.incrementAndGet();
                capturedPattern[0] = bcqlPattern;
                return List.of();
            }
        };
        ConcordanceHandlers handlers = new ConcordanceHandlers(countingExecutor, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/concordance/examples?seed=house&collocate=big&relation=noun_adj_predicates");
        handlers.handleConcordanceExamples(ex);
        assertEquals(200, ex.statusCode);
        assertEquals(1, callCount.get(), "executeBcqlQuery must be called exactly once per request");
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals(capturedPattern[0], body.path("bcql").asText(),
                "Response bcql must match the pattern that was passed to the executor");
    }

    @Test
    void handleConcordanceExamples_validParams_returns200() throws Exception {
        ConcordanceHandlers handlers = new ConcordanceHandlers(stubExecutor(), GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/concordance/examples?seed=house&collocate=big&relation=noun_adj_predicates");
        handlers.handleConcordanceExamples(ex);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
        assertEquals("house", body.path("seed").asText());
        assertEquals("big", body.path("collocate").asText());
        assertTrue(body.has("examples"), "Response must contain 'examples' key");
    }

    @Test
    void handleConcordanceExamples_missingWord1_returns400() throws Exception {
        ConcordanceHandlers handlers = new ConcordanceHandlers(null, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/concordance?collocate=house");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleConcordanceExamples, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleConcordanceExamples_missingWord2_returns400() throws Exception {
        ConcordanceHandlers handlers = new ConcordanceHandlers(null, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/concordance?seed=big");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleConcordanceExamples, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleConcordanceExamples_missingBothWords_returns400() throws Exception {
        ConcordanceHandlers handlers = new ConcordanceHandlers(null, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/concordance");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleConcordanceExamples, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }
}

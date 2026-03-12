package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation tests for {@link ConcordanceHandlers} — missing required query parameters.
 */
class ConcordanceHandlersTest {

    private static QueryExecutor stubExecutor() {
        return new QueryExecutor() {
            @Override public List<QueryResults.WordSketchResult> executeCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) { return List.of(); }
            @Override public List<QueryResults.ConcordanceResult> executeCqlQuery(
                    String cqlPattern, int maxResults) { return List.of(); }
            @Override public long getTotalFrequency(String lemma) { return 0L; }
            @Override public List<QueryResults.CollocateResult> executeBcqlQuery(
                    String bcqlPattern, int maxResults) {
                return List.of(new QueryResults.CollocateResult("The big house", null, 4, 7, "d1", "big", 1, 7.5));
            }
            @Override public List<QueryResults.WordSketchResult> executeSurfacePattern(
                    String lemma, String bcqlPattern, double minLogDice, int maxResults) { return List.of(); }
            @Override public List<QueryResults.WordSketchResult> executeDependencyPattern(
                    String lemma, String deprel, double minLogDice, int maxResults,
                    String headPosConstraint) { return List.of(); }
            @Override public void close() {}
        };
    }

    @Test
    void handleConcordanceExamples_validParams_returns200() throws Exception {
        ConcordanceHandlers handlers = new ConcordanceHandlers(stubExecutor(), GrammarConfigHelper.requireTestConfig());
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/concordance/examples?seed=house&collocate=big&relation=noun_adj_predicates");
        handlers.handleConcordanceExamples(ex);
        assertEquals(200, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertEquals("ok", body.getString("status"));
        assertEquals("house", body.getString("seed"));
        assertEquals("big", body.getString("collocate"));
        assertTrue(body.containsKey("examples"), "Response must contain 'examples' key");
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

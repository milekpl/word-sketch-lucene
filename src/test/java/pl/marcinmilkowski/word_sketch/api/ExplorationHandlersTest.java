package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer;
import pl.marcinmilkowski.word_sketch.model.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.Edge;
import pl.marcinmilkowski.word_sketch.model.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Happy-path tests for {@link ExplorationHandlers}.
 *
 * <p>Uses a stub {@link QueryExecutor} that returns empty results so that the
 * handlers complete without a real index and produce a valid 200 response.</p>
 */
class ExplorationHandlersTest {

    /** Stub executor that returns empty lists for all operations. */
    private static QueryExecutor emptyExecutor() {
        return new QueryExecutor() {
            @Override public List<QueryResults.WordSketchResult> executeCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) { return List.of(); }
            @Override public List<QueryResults.ConcordanceResult> executeCqlQuery(String p, int m) { return List.of(); }
            @Override public List<QueryResults.CollocateResult> executeBcqlQuery(String p, int m) { return List.of(); }
            @Override public long getTotalFrequency(String lemma) { return 0; }
            @Override public List<QueryResults.WordSketchResult> executeSurfacePattern(
                    String lemma, String pattern, double minLogDice, int maxResults) { return List.of(); }
            @Override public List<QueryResults.WordSketchResult> executeDependencyPattern(
                    String lemma, String deprel, double minLogDice, int maxResults,
                    String headPosConstraint) { return List.of(); }
            @Override public void close() {}
        };
    }

    private static ExplorationHandlers handlers() throws IOException {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        SemanticFieldExplorer explorer = new SemanticFieldExplorer(emptyExecutor(), config);
        return new ExplorationHandlers(config, explorer);
    }

    @Test
    void handleSemanticFieldExplore_validRequest_returns200() throws Exception {
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore?seed=house&relation=adj_predicate&top=5&min_shared=1");
        handlers().handleSemanticFieldExplore(ex);
        assertEquals(200, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertEquals("ok", body.getString("status"));
        assertEquals("house", body.getString("seed"));
        assertNotNull(body.get("edges"), "Response should contain an edges key");
    }

    @Test
    void handleSemanticFieldExploreMulti_twoSeeds_returns200() throws Exception {
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore-multi?seeds=theory,model&relation=adj_predicate&top=5&min_shared=1");
        handlers().handleSemanticFieldExploreMulti(ex);
        assertEquals(200, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertEquals("ok", body.getString("status"));
        assertNotNull(body.getJSONArray("seeds"), "Response should contain a seeds array");
        assertEquals(2, body.getIntValue("seed_count"));
    }

    @Test
    void handleSemanticFieldComparison_twoSeeds_returns200() throws Exception {
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/compare?seeds=theory,model&min_logdice=0.0");
        handlers().handleSemanticFieldComparison(ex);
        assertEquals(200, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertNotNull(body, "Response body should be valid JSON");
        assertEquals("ok", body.getString("status"));
        assertNotNull(body.getJSONArray("adjectives"), "Response should contain an adjectives array");
        assertNotNull(body.getJSONArray("seeds"), "Response should contain a seeds/nouns array");
        assertNotNull(body.get("adjectives_count"), "Response should contain adjectives_count");
        assertNotNull(body.get("seed_count"), "Response should contain seed_count");
    }

    @Test
    void handleSemanticFieldComparison_missingSeeds_throws() throws Exception {
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/compare?min_logdice=0.0");
        assertThrows(IllegalArgumentException.class,
            () -> handlers().handleSemanticFieldComparison(ex));
    }

    // ── Validation / negative-path tests (migrated from HandlersTest) ────────

    @Test
    void handleSemanticFieldExplore_missingSeed_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExplore, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExploreMulti_missingSeeds_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore-multi");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExploreMulti, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExploreMulti_oneSeed_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore-multi?seeds=theory");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExploreMulti, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldComparison_invalidNumericParam_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field?seeds=theory,model&min_logdice=notanumber");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldComparison, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExamples_missingAdjective_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/examples?noun=theory");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExamples, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExamples_missingNoun_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/examples?adjective=important");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExamples, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExamples_validParams_returns200() throws Exception {
        SemanticFieldExplorer explorer = new SemanticFieldExplorer(emptyExecutor(), null);
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), explorer);
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/examples?collocate=important&seed=theory&relation=noun_adj_predicates");
        handlers.handleSemanticFieldExamples(ex);
        assertEquals(200, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertEquals("ok", body.getString("status"));
        assertTrue(body.containsKey("examples"), "Response must contain 'examples' key");
        assertEquals("important", body.getString("collocate"));
        assertEquals("theory", body.getString("seed"));
    }

    @Test
    void handleSemanticFieldExploreMulti_nounsPerParam_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore-multi?seeds=theory,model&nouns_per=5");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExploreMulti, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExplore_unknownRelation_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore?seed=theory&relation=no_such_relation");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExplore, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExploreMulti_unknownRelation_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore-multi?seeds=theory,model&relation=no_such_relation");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExploreMulti, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void compare_edgeWeights_abstractHasCorrectWeightToTheory() throws Exception {
        QueryExecutor executor = collocatingExecutor(Map.of(
            "theory", List.of(wsr("abstract", 9.0)),
            "model",  List.of(wsr("abstract", 6.0))
        ));
        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, null);
        ComparisonResult result = explorer.compareCollocateProfiles(
                Set.of("theory", "model"), new ExplorationOptions(50, 0.0, 1));

        List<Edge> edges = ExploreResponseAssembler.buildEdges(result);
        assertFalse(edges.isEmpty(), "Should have edges");

        Edge theoryEdge = edges.stream()
            .filter(e -> e.target().equals("theory") && e.source().equals("abstract"))
            .findFirst().orElse(null);
        assertNotNull(theoryEdge, "Should have abstract→theory edge");
        assertEquals(9.0, theoryEdge.weight(), 0.001);
    }

    // ── Stub helpers ─────────────────────────────────────────────────────────

    private static QueryExecutor collocatingExecutor(Map<String, List<QueryResults.WordSketchResult>> map) {
        return new QueryExecutor() {
            @Override public List<QueryResults.WordSketchResult> executeCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) {
                return map.getOrDefault(lemma.toLowerCase(), List.of());
            }
            @Override public List<QueryResults.ConcordanceResult> executeCqlQuery(String p, int m) { return List.of(); }
            @Override public List<QueryResults.CollocateResult> executeBcqlQuery(String p, int m) { return List.of(); }
            @Override public long getTotalFrequency(String lemma) { return 0; }
            @Override public List<QueryResults.WordSketchResult> executeSurfacePattern(
                    String lemma, String pattern, double minLogDice, int maxResults) {
                return map.getOrDefault(lemma.toLowerCase(), List.of());
            }
            @Override public List<QueryResults.WordSketchResult> executeDependencyPattern(
                    String lemma, String deprel, double minLogDice, int maxResults,
                    String headPosConstraint) { return List.of(); }
            @Override public void close() {}
        };
    }

    private static QueryResults.WordSketchResult wsr(String lemma, double logDice) {
        return new QueryResults.WordSketchResult(lemma, "JJ", 10, logDice, 0.0, List.of());
    }
}

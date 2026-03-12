package pl.marcinmilkowski.word_sketch.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.exploration.spi.ExplorationService;
import pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer;
import pl.marcinmilkowski.word_sketch.model.exploration.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.exploration.Edge;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.sketch.*;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.StubQueryExecutor;

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
        return new StubQueryExecutor();
    }

    private static ExplorationHandlers handlers() throws IOException {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        ExplorationService explorer = new SemanticFieldExplorer(emptyExecutor(), config);
        return new ExplorationHandlers(explorer, config);
    }

    @Test
    void handleSemanticFieldExplore_validRequest_returns200() throws Exception {
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore?seed=house&relation=adj_predicate&top=5&min_shared=1");
        handlers().handleSemanticFieldExplore(ex);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
        assertEquals("house", body.path("seed").asText());
        assertNotNull(body.get("edges"), "Response should contain an edges key");
    }

    @Test
    void handleSemanticFieldExploreMulti_twoSeeds_returns200() throws Exception {
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore-multi?seeds=theory,model&relation=adj_predicate&top=5&min_shared=1");
        handlers().handleSemanticFieldExploreMulti(ex);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
        assertNotNull(body.get("seeds"), "Response should contain a seeds array");
        assertEquals(2, body.path("seed_count").asInt());
    }

    @Test
    void handleSemanticFieldComparison_twoSeeds_returns200() throws Exception {
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/compare?seeds=theory,model&min_logdice=0.0");
        handlers().handleSemanticFieldComparison(ex);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertNotNull(body, "Response body should be valid JSON");
        assertEquals("ok", body.path("status").asText());
        assertNotNull(body.get("collocates"), "Response should contain a collocates array");
        assertNotNull(body.get("seeds"), "Response should contain a seeds/nouns array");
        assertNotNull(body.get("collocates_count"), "Response should contain collocates_count");
        assertNotNull(body.get("seed_count"), "Response should contain seed_count");
    }

    @Test
    void handleSemanticFieldComparison_missingSeeds_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(null, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/compare?min_logdice=0.0");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldComparison, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    // ── Validation / negative-path tests (migrated from HandlersTest) ────────

    @Test
    void handleSemanticFieldExplore_missingSeed_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(null, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExplore, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExploreMulti_missingSeeds_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(null, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore-multi");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExploreMulti, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExploreMulti_oneSeed_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(null, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore-multi?seeds=theory");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExploreMulti, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldComparison_invalidNumericParam_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(null, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field?seeds=theory,model&min_logdice=notanumber");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldComparison, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExamples_missingAdjective_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(null, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/examples?noun=theory");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExamples, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExamples_missingNoun_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(null, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/examples?adjective=important");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExamples, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExamples_validParams_returns200() throws Exception {
        ExplorationService explorer = new SemanticFieldExplorer(emptyExecutor(), null);
        ExplorationHandlers handlers = new ExplorationHandlers(explorer, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/examples?collocate=important&seed=theory&relation=noun_adj_predicates");
        handlers.handleSemanticFieldExamples(ex);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
        assertTrue(body.has("examples"), "Response must contain 'examples' key");
        assertEquals("important", body.path("collocate").asText());
        assertEquals("theory", body.path("seed").asText());
    }

    @Test
    void handleSemanticFieldExploreMulti_nounsPerParam_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(null, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore-multi?seeds=theory,model&nouns_per=5");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExploreMulti, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExplore_unknownRelation_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(null, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore?seed=theory&relation=no_such_relation");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExplore, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExploreMulti_unknownRelation_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(null, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
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
        ExplorationService explorer = new SemanticFieldExplorer(executor, null);
        ComparisonResult result = explorer.compareCollocateProfiles(
                Set.of("theory", "model"), new ExplorationOptions(50, 0.0, 1));

        List<Edge> edges = ExploreResponseAssembler.buildComparisonEdges(result);
        assertFalse(edges.isEmpty(), "Should have edges");

        Edge theoryEdge = edges.stream()
            .filter(e -> e.target().equals("theory") && e.source().equals("abstract"))
            .findFirst().orElse(null);
        assertNotNull(theoryEdge, "Should have abstract→theory edge");
        assertEquals(9.0, theoryEdge.weight(), 0.001);
    }

    // ── Stub helpers ─────────────────────────────────────────────────────────

    private static QueryExecutor collocatingExecutor(Map<String, List<WordSketchResult>> map) {
        return new StubQueryExecutor() {
            @Override
            public List<WordSketchResult> executeCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) {
                return map.getOrDefault(lemma.toLowerCase(), List.of());
            }
            @Override
            public List<WordSketchResult> executeSurfacePattern(
                    String pattern, double minLogDice, int maxResults) {
                String lemma = StubQueryExecutor.extractLemmaFromPattern(pattern);
                return map.getOrDefault(lemma.toLowerCase(), List.of());
            }
        };
    }

    private static WordSketchResult wsr(String lemma, double logDice) {
        return new WordSketchResult(lemma, "JJ", 10, logDice, 0.0, List.of());
    }
}

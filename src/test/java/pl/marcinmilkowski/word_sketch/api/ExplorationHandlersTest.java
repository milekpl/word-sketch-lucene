package pl.marcinmilkowski.word_sketch.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.config.RelationUtils;
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
                "http://localhost/api/semantic-field/compare?seeds=theory,model&relation=adj_predicate&min_logdice=0.0");
        handlers().handleSemanticFieldComparison(ex);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertNotNull(body, "Response body should be valid JSON");
        assertEquals("ok", body.path("status").asText());
        assertEquals("noun_adj_predicates", body.path("parameters").path("relation").asText());
        assertNotNull(body.get("collocates"), "Response should contain a collocates array");
        assertNotNull(body.get("seeds"), "Response should contain a seeds/nouns array");
        assertNotNull(body.get("collocates_count"), "Response should contain collocates_count");
        assertNotNull(body.get("seed_count"), "Response should contain seed_count");
    }

    @Test
    void handleSemanticFieldComparison_withData_collocatesNonEmpty() throws Exception {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        RelationConfig relation = config.relation("subject_of").orElseThrow();
        String theoryPattern = RelationUtils.buildFullPattern(relation, "theory");
        String hypothesisPattern = RelationUtils.buildFullPattern(relation, "hypothesis");

        QueryExecutor executor = new StubQueryExecutor() {
            @Override
            public List<WordSketchResult> executeSurfaceCollocations(
                    String pattern, double minLogDice, int maxResults) {
                if (theoryPattern.equals(pattern)) {
                    return List.of(new WordSketchResult("explain", "VB", 10L, 8.5, 0.0, List.of()));
                }
                if (hypothesisPattern.equals(pattern)) {
                    return List.of(new WordSketchResult("explain", "VB", 10L, 7.8, 0.0, List.of()));
                }
                return List.of();
            }
        };
        ExplorationService explorer = new SemanticFieldExplorer(executor, config);
        ExplorationHandlers handlers = new ExplorationHandlers(explorer, config);

        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/compare?seeds=theory,hypothesis&relation=subject_of&min_logdice=0.0");
        handlers.handleSemanticFieldComparison(ex);

        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
        assertTrue(body.path("collocates").isArray(), "collocates must be an array");
        assertFalse(body.path("collocates").isEmpty(), "collocates must not be empty");
        assertEquals("subject_of", body.path("parameters").path("relation").asText());
        boolean hasExplain = false;
        for (var node : body.path("collocates")) {
            if ("explain".equals(node.path("word").asText())) {
                hasExplain = true;
                break;
            }
        }
        assertTrue(hasExplain, "collocates should contain 'explain'");
    }

    @Test
    void handleSemanticFieldComparison_differentRelationsProduceDifferentOutputs() throws Exception {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        RelationConfig predicateRelation = config.relation("noun_adj_predicates").orElseThrow();
        RelationConfig modifierRelation = config.relation("noun_modifiers").orElseThrow();

        String theoryPredicatePattern = RelationUtils.buildFullPattern(predicateRelation, "theory");
        String modelPredicatePattern = RelationUtils.buildFullPattern(predicateRelation, "model");
        String theoryModifierPattern = RelationUtils.buildFullPattern(modifierRelation, "theory");
        String modelModifierPattern = RelationUtils.buildFullPattern(modifierRelation, "model");

        QueryExecutor executor = new StubQueryExecutor() {
            @Override
            public List<WordSketchResult> executeSurfaceCollocations(
                    String pattern, double minLogDice, int maxResults) {
                return switch (pattern) {
                    case String p when theoryPredicatePattern.equals(p) -> List.of(wsr("abstract", 8.5));
                    case String p when modelPredicatePattern.equals(p) -> List.of(wsr("abstract", 7.5));
                    case String p when theoryModifierPattern.equals(p) -> List.of(wsr("formal", 8.2));
                    case String p when modelModifierPattern.equals(p) -> List.of(wsr("formal", 7.1));
                    default -> List.of();
                };
            }
        };

        ExplorationService explorer = new SemanticFieldExplorer(executor, config);
        ExplorationHandlers handlers = new ExplorationHandlers(explorer, config);

        MockExchangeFactory.MockExchange predicateEx = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/compare?seeds=theory,model&relation=adj_predicate&min_logdice=0.0");
        handlers.handleSemanticFieldComparison(predicateEx);
        ObjectNode predicateBody = HttpApiUtils.mapper().readValue(predicateEx.getResponseBodyAsString(), ObjectNode.class);

        MockExchangeFactory.MockExchange modifierEx = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/compare?seeds=theory,model&relation=adj_modifier&min_logdice=0.0");
        handlers.handleSemanticFieldComparison(modifierEx);
        ObjectNode modifierBody = HttpApiUtils.mapper().readValue(modifierEx.getResponseBodyAsString(), ObjectNode.class);

        assertEquals("noun_adj_predicates", predicateBody.path("parameters").path("relation").asText());
        assertEquals("noun_modifiers", modifierBody.path("parameters").path("relation").asText());
        assertEquals("abstract", predicateBody.path("collocates").get(0).path("word").asText());
        assertEquals("formal", modifierBody.path("collocates").get(0).path("word").asText());
    }

    @Test
    void handleSemanticFieldComparison_missingSeeds_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(stubService(), GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/compare?min_logdice=0.0");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldComparison, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    // ── Validation / negative-path tests (migrated from HandlersTest) ────────

    @Test
    void handleSemanticFieldExplore_missingSeed_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(stubService(), GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExplore, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExploreMulti_missingSeeds_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(stubService(), GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore-multi");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExploreMulti, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExploreMulti_oneSeed_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(stubService(), GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore-multi?seeds=theory");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExploreMulti, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldComparison_oneSeed_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(stubService(), GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/compare?seeds=theory&min_logdice=0.0");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldComparison, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldComparison_invalidNumericParam_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(stubService(), GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field?seeds=theory,model&min_logdice=notanumber");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldComparison, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExamples_missingAdjective_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(stubService(), GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/examples?noun=theory");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExamples, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExamples_missingNoun_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(stubService(), GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/examples?adjective=important");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExamples, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExamples_validParams_returns200() throws Exception {
        ExplorationService explorer = new SemanticFieldExplorer(emptyExecutor(), GrammarConfigHelper.requireTestConfig());
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
        ExplorationHandlers handlers = new ExplorationHandlers(stubService(), GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore-multi?seeds=theory,model&nouns_per=5");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExploreMulti, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExplore_unknownRelation_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(stubService(), GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore?seed=theory&relation=no_such_relation");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExplore, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExploreMulti_unknownRelation_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(stubService(), GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore-multi?seeds=theory,model&relation=no_such_relation");
        HttpApiUtils.wrapWithErrorHandling(handlers::handleSemanticFieldExploreMulti, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExplore_withData_seedCollocatesNonEmpty() throws Exception {
        QueryExecutor executor = collocatingExecutor(Map.of(
            "house", List.of(wsr("big", 8.5), wsr("old", 7.2))
        ));
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        ExplorationService explorer = new SemanticFieldExplorer(executor, config);
        ExplorationHandlers handlers = new ExplorationHandlers(explorer, config);

        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore?seed=house&relation=adj_predicate&top=5&min_shared=1");
        handlers.handleSemanticFieldExplore(ex);

        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
        assertEquals("house", body.path("seed").asText());
        assertTrue(body.path("seed_collocates").isArray(), "seed_collocates must be an array");
        assertFalse(body.path("seed_collocates").isEmpty(), "seed_collocates must not be empty");
        String firstWord = body.path("seed_collocates").get(0).path("word").asText();
        assertTrue(firstWord.equals("big") || firstWord.equals("old"),
                "first seed_collocate word should be 'big' or 'old', got: " + firstWord);
    }

    @Test
    void handleSemanticFieldExplore_withData_returnsSingleSeedOutputShape() throws Exception {
        QueryExecutor executor = new StubQueryExecutor() {
            @Override
            public List<WordSketchResult> executeSurfaceCollocations(
                    String pattern, double minLogDice, int maxResults) {
                String lemma = StubQueryExecutor.extractLemmaFromPattern(pattern);
                if (!"theory".equals(lemma)) {
                    return List.of();
                }
                return List.of(wsr("correct", 8.5), wsr("false", 7.2));
            }

            @Override
            public List<WordSketchResult> executeCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) {
                return switch (lemma) {
                    case "correct" -> List.of(wsr("answer", 6.2), wsr("response", 5.4));
                    case "false" -> List.of(wsr("answer", 5.1), wsr("belief", 4.7));
                    default -> List.of();
                };
            }
        };

        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        ExplorationService explorer = new SemanticFieldExplorer(executor, config);
        ExplorationHandlers handlers = new ExplorationHandlers(explorer, config);

        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore?seed=theory&relation=adj_predicate&top=5&min_shared=1&min_logdice=0.0");
        handlers.handleSemanticFieldExplore(ex);

        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);

        assertEquals("ok", body.path("status").asText());
        assertEquals("theory", body.path("seed").asText());
        assertEquals("noun_adj_predicates", body.path("parameters").path("relation").asText());
        assertEquals(5, body.path("parameters").path("top").asInt());
        assertEquals(1, body.path("parameters").path("min_shared").asInt());

        assertTrue(body.path("seed_collocates").isArray(), "seed_collocates must be an array");
        assertEquals(2, body.path("seed_collocates").size(), "seed_collocates should include both seed collocates");

        assertTrue(body.path("discovered_nouns").isArray(), "discovered_nouns must be an array");
        assertFalse(body.path("discovered_nouns").isEmpty(), "discovered_nouns must not be empty");
        var discovered = body.path("discovered_nouns").get(0);
        assertTrue(discovered.has("word"), "discovered_nouns entries must have word");
        assertTrue(discovered.has("shared_count"), "discovered_nouns entries must have shared_count");
        assertTrue(discovered.has("similarity_score"), "discovered_nouns entries must have similarity_score");
        assertTrue(discovered.has("avg_logdice"), "discovered_nouns entries must have avg_logdice");
        assertTrue(discovered.path("shared_collocates").isArray(), "shared_collocates must be an array");

        assertTrue(body.path("core_collocates").isArray(), "core_collocates must be an array");
        assertFalse(body.path("core_collocates").isEmpty(), "core_collocates must not be empty");
        var core = body.path("core_collocates").get(0);
        assertTrue(core.has("word"), "core_collocates entries must have word");
        assertTrue(core.has("coverage"), "core_collocates entries must have coverage");
        assertTrue(core.has("seed_logdice"), "core_collocates entries must have seed_logdice");

        assertTrue(body.path("edges").isArray(), "edges must be an array");
        assertFalse(body.path("edges").isEmpty(), "edges must not be empty");
        var edge = body.path("edges").get(0);
        assertTrue(edge.has("source"), "edge entries must have source");
        assertTrue(edge.has("target"), "edge entries must have target");
        assertTrue(edge.has("log_dice"), "edge entries must have log_dice");
        assertTrue(edge.has("type"), "edge entries must have type");
    }

    @Test
    void compare_edgeWeights_abstractHasCorrectWeightToTheory() throws Exception {
        QueryExecutor executor = collocatingExecutor(Map.of(
            "theory", List.of(wsr("abstract", 9.0)),
            "model",  List.of(wsr("abstract", 6.0))
        ));
        ExplorationService explorer = new SemanticFieldExplorer(executor, GrammarConfigHelper.requireTestConfig());
        ComparisonResult result = explorer.compareCollocateProfiles(
            Set.of("theory", "model"), GrammarConfigHelper.requireTestConfig().relation("noun_adj_predicates").orElseThrow(), new ExplorationOptions(50, 0.0, 1));

        List<Edge> edges = ComparisonResponseAssembler.buildComparisonEdges(result);
        assertFalse(edges.isEmpty(), "Should have edges");

        Edge theoryEdge = edges.stream()
            .filter(e -> e.target().equals("theory") && e.source().equals("abstract"))
            .findFirst().orElse(null);
        assertNotNull(theoryEdge, "Should have abstract→theory edge");
        assertEquals(9.0, theoryEdge.weight(), 0.001);
    }

    @Test
    void handleSemanticFieldExploreMulti_withData_returnsCommonCollocates() throws Exception {
        QueryExecutor executor = collocatingExecutor(Map.of(
            "theory",  List.of(wsr("important", 8.5), wsr("new", 7.0)),
            "model",   List.of(wsr("important", 7.8), wsr("old", 6.2))
        ));
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        ExplorationService explorer = new SemanticFieldExplorer(executor, config);
        ExplorationHandlers handlers = new ExplorationHandlers(explorer, config);

        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore-multi?seeds=theory,model&relation=adj_predicate&top=10&min_shared=1");
        handlers.handleSemanticFieldExploreMulti(ex);

        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
        assertTrue(body.path("seed_collocates").isArray(), "seed_collocates must be an array");
        assertFalse(body.path("seed_collocates").isEmpty(), "seed_collocates must not be empty");
        boolean hasImportant = false;
        for (var node : body.path("seed_collocates")) {
            if ("important".equals(node.path("word").asText())) {
                hasImportant = true;
                break;
            }
        }
        assertTrue(hasImportant, "seed_collocates should contain 'important' (shared by both seeds)");
    }

    // ── Error-path tests ─────────────────────────────────────────────────────

    @Test
    void handleSemanticFieldExplore_explorationException_returns503() throws Exception {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        ExplorationService failingService = new ExplorationService() {
            @Override
            public pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult exploreByRelation(
                    String seed, pl.marcinmilkowski.word_sketch.config.RelationConfig rc,
                    pl.marcinmilkowski.word_sketch.model.exploration.SingleSeedExplorationOptions opts) {
                throw new pl.marcinmilkowski.word_sketch.exploration.ExplorationException("index offline");
            }
            @Override
            public pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult exploreMultiSeed(
                    java.util.Set<String> seeds, pl.marcinmilkowski.word_sketch.config.RelationConfig rc,
                    pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions opts) {
                throw new pl.marcinmilkowski.word_sketch.exploration.ExplorationException("index offline");
            }
            @Override
            public pl.marcinmilkowski.word_sketch.model.exploration.ComparisonResult compareCollocateProfiles(
                    java.util.Set<String> seeds, pl.marcinmilkowski.word_sketch.config.RelationConfig rc,
                    pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions opts) {
                throw new pl.marcinmilkowski.word_sketch.exploration.ExplorationException("index offline");
            }
            @Override
            public pl.marcinmilkowski.word_sketch.model.exploration.FetchExamplesResult fetchExamples(
                    String seed, String collocate, pl.marcinmilkowski.word_sketch.config.RelationConfig rc,
                    pl.marcinmilkowski.word_sketch.model.exploration.FetchExamplesOptions opts) {
                throw new pl.marcinmilkowski.word_sketch.exploration.ExplorationException("index offline");
            }
        };
        ExplorationHandlers handlers = new ExplorationHandlers(failingService, config);

        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore?seed=house&relation=adj_predicate");
        handlers.handleSemanticFieldExplore(ex);
        assertEquals(503, ex.statusCode, "ExplorationException should return 503");
    }

    @Test
    void handleSemanticFieldExploreMulti_explorationException_returns503() throws Exception {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        ExplorationService failingService = new ExplorationService() {
            @Override
            public pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult exploreByRelation(
                    String seed, pl.marcinmilkowski.word_sketch.config.RelationConfig rc,
                    pl.marcinmilkowski.word_sketch.model.exploration.SingleSeedExplorationOptions opts) {
                throw new pl.marcinmilkowski.word_sketch.exploration.ExplorationException("index offline");
            }
            @Override
            public pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult exploreMultiSeed(
                    java.util.Set<String> seeds, pl.marcinmilkowski.word_sketch.config.RelationConfig rc,
                    pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions opts) {
                throw new pl.marcinmilkowski.word_sketch.exploration.ExplorationException("index offline");
            }
            @Override
            public pl.marcinmilkowski.word_sketch.model.exploration.ComparisonResult compareCollocateProfiles(
                    java.util.Set<String> seeds, pl.marcinmilkowski.word_sketch.config.RelationConfig rc,
                    pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions opts) {
                throw new pl.marcinmilkowski.word_sketch.exploration.ExplorationException("index offline");
            }
            @Override
            public pl.marcinmilkowski.word_sketch.model.exploration.FetchExamplesResult fetchExamples(
                    String seed, String collocate, pl.marcinmilkowski.word_sketch.config.RelationConfig rc,
                    pl.marcinmilkowski.word_sketch.model.exploration.FetchExamplesOptions opts) {
                throw new pl.marcinmilkowski.word_sketch.exploration.ExplorationException("index offline");
            }
        };
        ExplorationHandlers handlers = new ExplorationHandlers(failingService, config);

        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/semantic-field/explore-multi?seeds=theory,model&relation=adj_predicate");
        handlers.handleSemanticFieldExploreMulti(ex);
        assertEquals(503, ex.statusCode, "ExplorationException in multi-seed should return 503");
    }

    // ── Stub helpers ─────────────────────────────────────────────────────────

    /** Returns a minimal exploration service suitable for validation (parameter-checking) tests only. */
    private static ExplorationService stubService() {
        return new SemanticFieldExplorer(emptyExecutor(), GrammarConfigHelper.requireTestConfig());
    }

    private static QueryExecutor collocatingExecutor(Map<String, List<WordSketchResult>> map) {
        return new StubQueryExecutor() {
            @Override
            public List<WordSketchResult> executeCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) {
                return map.getOrDefault(lemma.toLowerCase(), List.of());
            }
            @Override
            public List<WordSketchResult> executeSurfaceCollocations(
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

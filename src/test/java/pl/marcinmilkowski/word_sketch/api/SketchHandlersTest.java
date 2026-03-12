package pl.marcinmilkowski.word_sketch.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.config.RelationType;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.StubQueryExecutor;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Happy-path tests for {@link SketchHandlers}.
 *
 * <p>Uses a stub {@link QueryExecutor} that returns fixed collocate data so that all three
 * sketch response shapes — full sketch, single-relation sketch, and dep sketch — can be
 * verified at the handler level without a live BlackLab index.</p>
 */
class SketchHandlersTest {

    /** Stub executor returning a fixed collocate result for any query. */
    private static QueryExecutor stubExecutor() {
        QueryResults.WordSketchResult stub = new QueryResults.WordSketchResult(
                "important", "JJ", 100L, 7.5, 0.01, List.of("it is important"));
        return new StubQueryExecutor() {
            @Override
            public List<QueryResults.WordSketchResult> executeCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) {
                return List.of(stub);
            }
            @Override
            public long getTotalFrequency(String lemma) { return 10000L; }
            @Override
            public List<QueryResults.WordSketchResult> executeSurfacePattern(
                    String pattern, double minLogDice, int maxResults) { return List.of(stub); }
            @Override
            public List<QueryResults.WordSketchResult> executeDependencyPattern(
                    String lemma, String deprel, double minLogDice, int maxResults,
                    String headPosConstraint) { return List.of(stub); }
        };
    }

    private static SketchHandlers handlers() throws IOException {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        return new SketchHandlers(stubExecutor(), config);
    }

    @Test
    void handleSketchRequest_fullSketch_returns200WithRelationsMap() throws Exception {
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange("http://localhost/api/sketch/theory");
        handlers().routeSketchRequest(ex);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.MAPPER.readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
        assertEquals("theory", body.path("lemma").asText());
        assertNotNull(body.get("relations"), "Full sketch response should contain a relations map");
    }

    @Test
    void handleSketchRequest_singleRelation_returns200WithCollocations() throws Exception {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        // Pick the first surface relation by ID (IDs are URL-safe)
        String firstRelationId = config.relations().stream()
                .filter(r -> r.relationType().isPresent())
                .findFirst()
                .map(pl.marcinmilkowski.word_sketch.config.RelationConfig::id)
                .orElse("adj_predicate");
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange("http://localhost/api/sketch/theory/" + firstRelationId);
        new SketchHandlers(stubExecutor(), config).routeSketchRequest(ex);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.MAPPER.readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
        assertEquals("theory", body.path("lemma").asText());
        assertNotNull(body.get("relations"), "Single-relation sketch should contain a relations map");
        // collocations are nested under the relation ID
        assertNotNull(body.path("relations").get(firstRelationId),
            "Relations map should contain an entry for the requested relation");
    }

    @Test
    void handleSurfaceRelations_returns200WithRelationsArray() throws Exception {
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange("http://localhost/api/sketch/surface-relations");
        handlers().handleRelationsForType(ex, RelationType.SURFACE);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.MAPPER.readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
        assertNotNull(body.get("relations"), "Surface-relations response should contain a relations array");
    }

    // ── Validation / negative-path tests (migrated from HandlersTest) ────────

    @Test
    void handleSketchRequest_missingLemma_returns400() throws Exception {
        SketchHandlers handlers = new SketchHandlers(null, null);
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange("http://localhost/api/sketch/");
        HttpApiUtils.wrapWithErrorHandling(handlers::routeSketchRequest, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSketchRequest_unknownRelation_returns400() throws Exception {
        SketchHandlers handlers = new SketchHandlers(stubExecutor(), GrammarConfigHelper.requireTestConfig());
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/sketch/house/no_such_relation");
        HttpApiUtils.wrapWithErrorHandling(handlers::routeSketchRequest, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSketchRequest_missingRelationType_returns200() throws Exception {
        QueryExecutor executor = collocatingExecutor(Map.of(
            "theory", List.of(wsr("abstract", 8.0))
        ));
        SketchHandlers handlers = new SketchHandlers(executor, GrammarConfigHelper.requireTestConfig());
        TestExchangeFactory.MockExchange ex = new TestExchangeFactory.MockExchange(
                "http://localhost/api/sketch/theory?lemma=theory");
        handlers.routeSketchRequest(ex);
        assertEquals(200, ex.statusCode);
    }

    // ── Stub helpers ─────────────────────────────────────────────────────────

    private static QueryExecutor collocatingExecutor(Map<String, List<QueryResults.WordSketchResult>> map) {
        return new StubQueryExecutor() {
            @Override
            public List<QueryResults.WordSketchResult> executeCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) {
                return map.getOrDefault(lemma.toLowerCase(), List.of());
            }
            @Override
            public List<QueryResults.WordSketchResult> executeSurfacePattern(
                    String pattern, double minLogDice, int maxResults) {
                String lemma = StubQueryExecutor.extractLemmaFromPattern(pattern);
                return map.getOrDefault(lemma.toLowerCase(), List.of());
            }
        };
    }

    private static QueryResults.WordSketchResult wsr(String lemma, double logDice) {
        return new QueryResults.WordSketchResult(lemma, "JJ", 10, logDice, 0.0, List.of());
    }
}

package pl.marcinmilkowski.word_sketch.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.model.RelationType;
import pl.marcinmilkowski.word_sketch.model.sketch.*;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.StubQueryExecutor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

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
        WordSketchResult stub = new WordSketchResult(
                "important", "JJ", 100L, 7.5, 0.01, List.of("it is important"));
        return new StubQueryExecutor() {
            @Override
            public List<WordSketchResult> executeCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) {
                return List.of(stub);
            }
            @Override
            public long getTotalFrequency(String lemma) { return 10000L; }
            @Override
            public List<WordSketchResult> executeSurfaceCollocations(
                    String pattern, double minLogDice, int maxResults) { return List.of(stub); }
            @Override
            public List<WordSketchResult> executeDependencyPattern(
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
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange("http://localhost/api/sketch/theory");
        handlers().routeSketchRequest(ex);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
        assertEquals("theory", body.path("lemma").asText());
        assertNotNull(body.get("patterns"), "Full sketch response should contain a patterns map");
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
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange("http://localhost/api/sketch/theory/" + firstRelationId);
        new SketchHandlers(stubExecutor(), config).routeSketchRequest(ex);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
        assertEquals("theory", body.path("lemma").asText());
        assertNotNull(body.get("patterns"), "Single-relation sketch should contain a patterns map");
        // collocations are nested under the relation ID
        assertNotNull(body.path("patterns").get(firstRelationId),
            "Patterns map should contain an entry for the requested relation");
    }

    @Test
    void handleSurfaceRelations_returns200WithRelationsArray() throws Exception {
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange("http://localhost/api/sketch/surface-relations");
        handlers().handleRelationsForType(ex, RelationType.SURFACE);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
        assertNotNull(body.get("relations"), "Surface-relations response should contain a relations array");
        assertFalse(body.path("relations").isEmpty(), "Surface-relations response should not be empty");
        assertTrue(body.path("relations").get(0).has("head_pos_group"), "Relations catalogue should expose head_pos_group");
        assertTrue(body.path("relations").get(0).has("collocate_pos_group"), "Relations catalogue should expose collocate_pos_group");
    }
    @Test
    void handleSketchRequest_fullSketch_includesVerbSubjectsRelation() throws Exception {
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange("http://localhost/api/sketch/predict");
        handlers().routeSketchRequest(ex);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        ObjectNode patterns = (ObjectNode) body.get("patterns");
        assertTrue(patterns.has("verb_subjects"), "Full sketch should include the verb_subjects relation");
    }
    // ── Validation / negative-path tests (migrated from HandlersTest) ────────

    @Test
    void handleSketchRequest_missingLemma_returns400() throws Exception {
        SketchHandlers handlers = new SketchHandlers(null, null);
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange("http://localhost/api/sketch/");
        HttpApiUtils.wrapWithErrorHandling(handlers::routeSketchRequest, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSketchRequest_unknownRelation_returns400() throws Exception {
        SketchHandlers handlers = new SketchHandlers(stubExecutor(), GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/sketch/house/no_such_relation");
        HttpApiUtils.wrapWithErrorHandling(handlers::routeSketchRequest, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    /**
     * Data-shape test: the full-sketch response must include a non-empty collocations array
     * for at least one relation, and the first entry must carry the lemma and logDice that
     * the stub injected ("important", 7.5).
     */
    @Test
    void handleSketchRequest_fullSketch_firstCollocateHasCorrectLemmaAndLogDice() throws Exception {
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange("http://localhost/api/sketch/theory");
        handlers().routeSketchRequest(ex);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        ObjectNode relations = (ObjectNode) body.get("patterns");
        assertNotNull(relations, "patterns map must be present");
        assertFalse(relations.isEmpty(), "patterns map must be non-empty");
        // The stub returns "important" (JJ, 7.5) for every query — verify first relation entry
        String firstRelId = relations.fieldNames().next();
        com.fasterxml.jackson.databind.JsonNode firstRel = relations.get(firstRelId);
        com.fasterxml.jackson.databind.JsonNode collocations = firstRel.get("collocations");
        assertNotNull(collocations, "collocations array must be present");
        assertFalse(collocations.isEmpty(), "collocations must contain at least one entry");
        assertEquals("important", collocations.get(0).path("lemma").asText(),
                "first collocate lemma must be 'important' (from stub)");
        assertEquals(7.5, collocations.get(0).path("logDice").asDouble(), 0.001,
                "first collocate logDice must be 7.5 (from stub)");
    }

    @Test
    void handleSketchRequest_missingRelationType_returns200() throws Exception {
        QueryExecutor executor = collocatingExecutor(Map.of(
            "theory", List.of(wsr("abstract", 8.0))
        ));
        SketchHandlers handlers = new SketchHandlers(executor, GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/sketch/theory?lemma=theory");
        handlers.routeSketchRequest(ex);
        assertEquals(200, ex.statusCode);
    }

    @Test
    void handleSketchRequest_posFilter_prunesSurfaceRelationsByCollocateGroup() throws Exception {
        MockExchangeFactory.MockExchange allEx = new MockExchangeFactory.MockExchange("http://localhost/api/sketch/theory");
        handlers().routeSketchRequest(allEx);
        ObjectNode allBody = HttpApiUtils.mapper().readValue(allEx.getResponseBodyAsString(), ObjectNode.class);
        ObjectNode allPatterns = (ObjectNode) allBody.get("patterns");

        MockExchangeFactory.MockExchange filteredEx = new MockExchangeFactory.MockExchange("http://localhost/api/sketch/theory?pos=adj");
        handlers().routeSketchRequest(filteredEx);
        assertEquals(200, filteredEx.statusCode);

        ObjectNode filteredBody = HttpApiUtils.mapper().readValue(filteredEx.getResponseBodyAsString(), ObjectNode.class);
        ObjectNode filteredPatterns = (ObjectNode) filteredBody.get("patterns");
        assertNotNull(filteredPatterns, "Filtered sketch should contain a patterns map");
        assertFalse(filteredPatterns.isEmpty(), "Adj filter should keep adjective relations");
        assertTrue(filteredPatterns.size() < allPatterns.size(), "POS filter should reduce relation count");
        filteredPatterns.fields().forEachRemaining(entry ->
                assertEquals("adj", entry.getValue().path("collocate_pos_group").asText(),
                        "Filtered relation " + entry.getKey() + " must target adjective collocates"));
    }

    @Test
    void handleSketchRequest_headPosFilter_prunesSurfaceRelationsByHeadGroup() throws Exception {
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/sketch/predict?head_pos=verb");
        handlers().routeSketchRequest(ex);
        assertEquals(200, ex.statusCode);

        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        ObjectNode patterns = (ObjectNode) body.get("patterns");
        assertNotNull(patterns, "Filtered sketch should contain a patterns map");
        assertTrue(patterns.has("verb_subjects"), "Verb head filter should retain verb_subjects");
        assertTrue(patterns.has("object_of"), "Verb head filter should retain object_of");
        assertFalse(patterns.has("subject_of"), "Verb head filter should exclude noun-head subject_of");
        assertFalse(patterns.has("noun_verbs"), "Verb head filter should exclude noun-head noun_verbs");
    }

    @Test
    void handleSketchRequest_invalidPosFilter_returns400() throws Exception {
        SketchHandlers handlers = new SketchHandlers(stubExecutor(), GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/sketch/theory?pos=banana");
        HttpApiUtils.wrapWithErrorHandling(handlers::routeSketchRequest, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSketchRequest_invalidHeadPosFilter_returns400() throws Exception {
        SketchHandlers handlers = new SketchHandlers(stubExecutor(), GrammarConfigHelper.requireTestConfig());
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api/sketch/theory?head_pos=banana");
        HttpApiUtils.wrapWithErrorHandling(handlers::routeSketchRequest, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSketchRequest_fullSketch_executesSurfaceRelationsConcurrently() throws Exception {
        AtomicInteger currentQueries = new AtomicInteger();
        AtomicInteger maxConcurrentQueries = new AtomicInteger();
        QueryExecutor executor = new StubQueryExecutor() {
            @Override
            public List<WordSketchResult> executeSurfaceCollocations(
                    String pattern, double minLogDice, int maxResults) {
                int active = currentQueries.incrementAndGet();
                maxConcurrentQueries.accumulateAndGet(active, Math::max);
                try {
                    LockSupport.parkNanos(25_000_000L);
                    return List.of(wsr("important", 7.5));
                } finally {
                    currentQueries.decrementAndGet();
                }
            }
        };

        SketchHandlers handlers = new SketchHandlers(executor, GrammarConfigHelper.requireTestConfig());
        try {
            MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange("http://localhost/api/sketch/theory");
            handlers.routeSketchRequest(ex);
            assertEquals(200, ex.statusCode);
        } finally {
            handlers.close();
        }

        assertTrue(maxConcurrentQueries.get() > 1,
                "Full sketch queries should overlap relation lookups, but max concurrency was " + maxConcurrentQueries.get());
    }

    @Test
    void handleSketchRequest_fullSketch_preservesSurfaceRelationOrder() throws Exception {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        List<String> expectedOrder = config.relations().stream()
                .filter(rel -> rel.relationType().orElse(null) == RelationType.SURFACE)
                .map(pl.marcinmilkowski.word_sketch.config.RelationConfig::id)
                .toList();

        QueryExecutor executor = new StubQueryExecutor() {
            @Override
            public List<WordSketchResult> executeSurfaceCollocations(
                    String pattern, double minLogDice, int maxResults) {
                int delayBucket = Math.floorMod(pattern.hashCode(), 7);
                LockSupport.parkNanos((long) (7 - delayBucket) * 5_000_000L);
                return List.of(wsr("important", 7.5));
            }
        };

        SketchHandlers handlers = new SketchHandlers(executor, config);
        try {
            MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange("http://localhost/api/sketch/theory");
            handlers.routeSketchRequest(ex);
            assertEquals(200, ex.statusCode);

            ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
            ObjectNode relations = (ObjectNode) body.get("patterns");
            List<String> actualOrder = new ArrayList<>();
            relations.fieldNames().forEachRemaining(actualOrder::add);
            assertEquals(expectedOrder, actualOrder);
        } finally {
            handlers.close();
        }
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

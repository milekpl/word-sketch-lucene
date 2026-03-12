package pl.marcinmilkowski.word_sketch.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.exploration.ExplorationService;
import pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.StubQueryExecutor;
import pl.marcinmilkowski.word_sketch.model.QueryResults;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lifecycle and route-registration tests for {@link WordSketchApiServer}.
 *
 * <p>Starts the server on an ephemeral port and verifies that it binds, routes
 * HTTP requests to the correct handlers, and shuts down cleanly. No real corpus
 * index is required: a stub {@link QueryExecutor} satisfies the server's dependencies.</p>
 */
@DisplayName("WordSketchApiServer")
class WordSketchApiServerTest {

    private static final int TEST_PORT = 18080;

    private WordSketchApiServer server;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** Minimal stub executor that returns empty results for every query. */
    private static final QueryExecutor STUB_EXECUTOR = new StubQueryExecutor();

    @BeforeEach
    void startServer() throws IOException {
        var grammar = GrammarConfigHelper.requireTestConfig();
        ExplorationService explorer = new SemanticFieldExplorer(STUB_EXECUTOR, grammar);
        server = new WordSketchApiServer(STUB_EXECUTOR, explorer, TEST_PORT, grammar);
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    @DisplayName("GET /health returns 200 with status=ok")
    void health_returns200() throws Exception {
        HttpResponse<String> response = get("/health");
        assertEquals(200, response.statusCode());
        ObjectNode body = HttpApiUtils.MAPPER.readValue(response.body(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
    }

    @Test
    @DisplayName("GET /api/sketch/ with missing lemma returns 400")
    void sketchEndpoint_missingLemma_returns400() throws Exception {
        HttpResponse<String> response = get("/api/sketch/");
        assertEquals(400, response.statusCode());
    }

    @Test
    @DisplayName("GET /api/relations returns 200")
    void relationsEndpoint_returns200() throws Exception {
        HttpResponse<String> response = get("/api/relations");
        assertEquals(200, response.statusCode());
    }

    @Test
    @DisplayName("stop() terminates the server cleanly — subsequent requests fail to connect")
    void stop_serverBecomesUnreachable() throws Exception {
        // Verify server is up
        assertEquals(200, get("/health").statusCode());
        // Stop and verify unreachable
        server.stop();
        server = null;
        assertThrows(Exception.class, () -> get("/health"),
            "Expected connection refused after server.stop()");
    }

    // ── Data-flow integration tests ───────────────────────────────────────────

    /**
     * Verifies the full HTTP→handler→serialisation path for {@code GET /api/sketch/{lemma}}
     * using a stub executor that returns known collocate data.
     */
    @Test
    @DisplayName("GET /api/sketch/{lemma} — response contains expected lemma, relations map, and collocates")
    void sketchEndpoint_withCannedData_returnsExpectedJson() throws Exception {
        QueryResults.WordSketchResult empirical = new QueryResults.WordSketchResult(
                "empirical", "JJ", 500L, 8.3, 0.005, List.of("empirical theory"));
        QueryResults.WordSketchResult scientific = new QueryResults.WordSketchResult(
                "scientific", "JJ", 420L, 7.9, 0.004, List.of("scientific theory"));

        QueryExecutor richStub = new StubQueryExecutor() {
            @Override
            public List<QueryResults.WordSketchResult> executeCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) {
                return "theory".equalsIgnoreCase(lemma) ? List.of(empirical, scientific) : List.of();
            }
            @Override
            public long getTotalFrequency(String lemma) { return 50_000L; }
            @Override
            public List<QueryResults.WordSketchResult> executeSurfacePattern(
                    String pattern, double minLogDice, int maxResults) {
                return List.of(empirical, scientific);
            }
        };

        GrammarConfig grammar = GrammarConfigHelper.requireTestConfig();
        ExplorationService explorer = new SemanticFieldExplorer(richStub, grammar);
        WordSketchApiServer richServer = new WordSketchApiServer(richStub, explorer, TEST_PORT + 1, grammar);
        richServer.start();
        try {
            HttpResponse<String> response = get(TEST_PORT + 1, "/api/sketch/theory");
            assertEquals(200, response.statusCode());
            ObjectNode body = HttpApiUtils.MAPPER.readValue(response.body(), ObjectNode.class);
            assertEquals("ok", body.path("status").asText());
            assertEquals("theory", body.path("lemma").asText());
            JsonNode relations = body.path("relations");
            assertFalse(relations.isMissingNode(), "Response must contain a 'relations' map");
            assertTrue(relations.isObject() || relations.isArray(),
                "relations must be an object or array, was: " + relations.getNodeType());

            // At least one relation entry must contain a collocate with our canned lemma
            String bodyText = response.body();
            assertTrue(bodyText.contains("empirical") || bodyText.contains("scientific"),
                "Response body must include at least one of the canned collocates; body=" + bodyText);
            // logDice scores must be present
            assertTrue(bodyText.contains("8.3") || bodyText.contains("7.9"),
                "Response body must include logDice scores from canned data; body=" + bodyText);
        } finally {
            richServer.stop();
        }
    }

    /**
     * Verifies the full HTTP→handler→serialisation path for
     * {@code GET /api/semantic-field/explore} using a stub executor with canned data.
     */
    @Test
    @DisplayName("GET /api/semantic-field/explore — response contains seed, edges, and collocate from canned data")
    void exploreEndpoint_withCannedData_returnsEdgesAndCollocates() throws Exception {
        QueryResults.WordSketchResult theoretical = new QueryResults.WordSketchResult(
                "theoretical", "JJ", 300L, 7.1, 0.003, List.of("theoretical theory"));

        QueryExecutor richStub = new StubQueryExecutor() {
            @Override
            public List<QueryResults.WordSketchResult> executeCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) {
                return List.of(theoretical);
            }
        };

        GrammarConfig grammar = GrammarConfigHelper.requireTestConfig();
        ExplorationService explorer = new SemanticFieldExplorer(richStub, grammar);
        WordSketchApiServer richServer = new WordSketchApiServer(richStub, explorer, TEST_PORT + 2, grammar);
        richServer.start();
        try {
            HttpResponse<String> response = get(TEST_PORT + 2,
                    "/api/semantic-field/explore?seed=theory&relation=adj_predicate&top=5&min_shared=1");
            assertEquals(200, response.statusCode());
            ObjectNode body = HttpApiUtils.MAPPER.readValue(response.body(), ObjectNode.class);
            assertEquals("ok", body.path("status").asText());
            assertEquals("theory", body.path("seed").asText());
            assertFalse(body.path("edges").isMissingNode(), "Response must contain an 'edges' key");
            assertTrue(response.body().contains("theoretical"),
                "Response body must include the canned collocate; body=" + response.body());
        } finally {
            richServer.stop();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return get(TEST_PORT, path);
    }

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .GET()
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

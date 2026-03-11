package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.model.QueryResults;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;

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
    private static final QueryExecutor STUB_EXECUTOR = new QueryExecutor() {
        @Override
        public List<QueryResults.WordSketchResult> executeCollocations(
                String lemma, String cqlPattern, double minLogDice, int maxResults) {
            return Collections.emptyList();
        }

        @Override
        public List<QueryResults.ConcordanceResult> executeCqlQuery(String cqlPattern, int maxResults) {
            return Collections.emptyList();
        }

        @Override
        public List<QueryResults.CollocateResult> executeBcqlQuery(String bcqlQuery, int maxResults) {
            return Collections.emptyList();
        }

        @Override
        public long getTotalFrequency(String lemma) { return 0; }

        @Override
        public List<QueryResults.WordSketchResult> executeSurfacePattern(
                String lemma, String pattern, double minLogDice, int maxResults) {
            return Collections.emptyList();
        }

        @Override
        public List<QueryResults.WordSketchResult> executeDependencyPattern(
                String lemma, String deprel, double minLogDice, int maxResults) {
            return Collections.emptyList();
        }

        @Override
        public List<QueryResults.WordSketchResult> executeDependencyPatternWithPos(
                String lemma, String deprel, double minLogDice, int maxResults,
                String headPosConstraint) {
            return Collections.emptyList();
        }

        @Override
        public void close() {}
    };

    @BeforeEach
    void startServer() throws IOException {
        var grammar = GrammarConfigHelper.requireTestConfig();
        var explorer = new SemanticFieldExplorer(STUB_EXECUTOR, grammar);
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
        JSONObject body = JSON.parseObject(response.body());
        assertEquals("ok", body.getString("status"));
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

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + TEST_PORT + path))
            .GET()
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

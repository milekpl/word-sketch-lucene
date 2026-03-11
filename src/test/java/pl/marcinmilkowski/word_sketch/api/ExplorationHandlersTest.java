package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Happy-path tests for {@link ExplorationHandlers}.
 *
 * <p>Uses a stub {@link QueryExecutor} that returns empty results so that the
 * handlers complete without a real index and produce a valid 200 response.</p>
 */
class ExplorationHandlersTest {

    static class MockExchange extends HttpExchange {
        private final URI uri;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        int statusCode = -1;

        MockExchange(String uriString) {
            try { this.uri = new URI(uriString); } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Override public URI getRequestURI() { return uri; }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int rCode, long responseLength) { this.statusCode = rCode; }
        @Override public void close() {}
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(0); }
        @Override public int getResponseCode() { return statusCode; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress(0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public HttpContext getHttpContext() { return null; }
        @Override public HttpPrincipal getPrincipal() { return null; }

        String getResponseBodyAsString() { return responseBody.toString(java.nio.charset.StandardCharsets.UTF_8); }
    }

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
                    String lemma, String deprel, double minLogDice, int maxResults) { return List.of(); }
            @Override public List<QueryResults.WordSketchResult> executeDependencyPatternWithPos(
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
        MockExchange ex = new MockExchange(
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
        MockExchange ex = new MockExchange(
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
        MockExchange ex = new MockExchange(
                "http://localhost/api/semantic-field/compare?seeds=theory,model&min_logdice=0.0");
        handlers().handleSemanticFieldComparison(ex);
        assertEquals(200, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertNotNull(body, "Response body should be valid JSON");
    }
}

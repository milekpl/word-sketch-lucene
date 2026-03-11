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
 * Happy-path tests for {@link SketchHandlers}.
 *
 * <p>Uses a stub {@link QueryExecutor} that returns fixed collocate data so that all three
 * sketch response shapes — full sketch, single-relation sketch, and dep sketch — can be
 * verified at the handler level without a live BlackLab index.</p>
 */
class SketchHandlersTest {

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
        @Override public void sendResponseHeaders(int code, long len) { this.statusCode = code; }
        @Override public int getResponseCode() { return statusCode; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) {}
        @Override public void setStreams(InputStream i, OutputStream o) {}
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() {}
        @Override public HttpPrincipal getPrincipal() { return null; }

        String getResponseBodyAsString() { return responseBody.toString(java.nio.charset.StandardCharsets.UTF_8); }
    }

    /** Stub executor returning a fixed collocate result for any query. */
    private static QueryExecutor stubExecutor() {
        QueryResults.WordSketchResult stub = new QueryResults.WordSketchResult(
                "important", "JJ", 100L, 7.5, 0.01, List.of("it is important"));
        return new QueryExecutor() {
            @Override public List<QueryResults.WordSketchResult> executeCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) {
                return List.of(stub);
            }
            @Override public List<QueryResults.ConcordanceResult> executeCqlQuery(String p, int m) { return List.of(); }
            @Override public List<QueryResults.CollocateResult> executeBcqlQuery(String p, int m) { return List.of(); }
            @Override public long getTotalFrequency(String lemma) { return 10000L; }
            @Override public List<QueryResults.WordSketchResult> executeSurfacePattern(
                    String lemma, String pattern, double minLogDice, int maxResults) { return List.of(stub); }
            @Override public List<QueryResults.WordSketchResult> executeDependencyPattern(
                    String lemma, String deprel, double minLogDice, int maxResults) { return List.of(stub); }
            @Override public List<QueryResults.WordSketchResult> executeDependencyPatternWithPos(
                    String lemma, String deprel, double minLogDice, int maxResults, String headPosConstraint) {
                return List.of(stub);
            }
            @Override public void close() {}
        };
    }

    private static SketchHandlers handlers() throws IOException {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        return new SketchHandlers(stubExecutor(), config);
    }

    @Test
    void handleSketchRequest_fullSketch_returns200WithRelationsMap() throws Exception {
        MockExchange ex = new MockExchange("http://localhost/api/sketch/theory");
        handlers().handleSketchRequest(ex);
        assertEquals(200, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertEquals("ok", body.getString("status"));
        assertEquals("theory", body.getString("lemma"));
        assertNotNull(body.getJSONObject("relations"), "Full sketch response should contain a relations map");
    }

    @Test
    void handleSketchRequest_singleRelation_returns200WithCollocations() throws Exception {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        // Pick the first surface relation by ID (IDs are URL-safe)
        String firstRelationId = config.getRelations().stream()
                .filter(r -> r.relationType().isPresent())
                .findFirst()
                .map(pl.marcinmilkowski.word_sketch.config.RelationConfig::id)
                .orElse("adj_predicate");
        MockExchange ex = new MockExchange("http://localhost/api/sketch/theory/" + firstRelationId);
        new SketchHandlers(stubExecutor(), config).handleSketchRequest(ex);
        assertEquals(200, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertEquals("ok", body.getString("status"));
        assertEquals("theory", body.getString("lemma"));
        assertNotNull(body.getJSONObject("relations"), "Single-relation sketch should contain a relations map");
        // collocations are nested under the relation ID
        assertNotNull(body.getJSONObject("relations").getJSONObject(firstRelationId),
            "Relations map should contain an entry for the requested relation");
    }

    @Test
    void handleSurfaceRelations_returns200WithRelationsArray() throws Exception {
        MockExchange ex = new MockExchange("http://localhost/api/sketch/surface-relations");
        handlers().handleSurfaceRelations(ex);
        assertEquals(200, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertEquals("ok", body.getString("status"));
        assertNotNull(body.getJSONArray("relations"), "Surface-relations response should contain a relations array");
    }
}

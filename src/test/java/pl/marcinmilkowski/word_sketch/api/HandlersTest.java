package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HandlersTest {

    static class MockExchange extends HttpExchange {
        private final URI uri;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        int statusCode = -1;
        private long responseLength = -1;

        MockExchange(String uriString) {
            try { this.uri = new URI(uriString); } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Override public URI getRequestURI() { return uri; }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
            this.statusCode = rCode; this.responseLength = responseLength;
        }
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

        String getResponseBodyAsString() { return responseBody.toString(); }
    }

    // --- SketchHandlers tests ---

    @Test
    void handleSketchRequest_missingLemma_returns400() throws Exception {
        SketchHandlers handlers = new SketchHandlers(null, null);
        MockExchange ex = new MockExchange("http://localhost/api/sketch/");
        handlers.handleSketchRequest(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleConcordanceExamples_missingWord1_returns400() throws Exception {
        SketchHandlers handlers = new SketchHandlers(null, null);
        MockExchange ex = new MockExchange("http://localhost/api/concordance?word2=house");
        handlers.handleConcordanceExamples(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleConcordanceExamples_missingWord2_returns400() throws Exception {
        SketchHandlers handlers = new SketchHandlers(null, null);
        MockExchange ex = new MockExchange("http://localhost/api/concordance?word1=big");
        handlers.handleConcordanceExamples(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleConcordanceExamples_missingBothWords_returns400() throws Exception {
        SketchHandlers handlers = new SketchHandlers(null, null);
        MockExchange ex = new MockExchange("http://localhost/api/concordance");
        handlers.handleConcordanceExamples(ex);
        assertEquals(400, ex.statusCode);
    }

    // --- ExplorationHandlers tests ---

    @Test
    void handleSemanticFieldExplore_missingSeed_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        MockExchange ex = new MockExchange("http://localhost/api/semantic-field/explore");
        handlers.handleSemanticFieldExplore(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExploreMulti_missingSeeds_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        MockExchange ex = new MockExchange("http://localhost/api/semantic-field/explore-multi");
        handlers.handleSemanticFieldExploreMulti(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExploreMulti_oneSeed_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        MockExchange ex = new MockExchange("http://localhost/api/semantic-field/explore-multi?seeds=theory");
        handlers.handleSemanticFieldExploreMulti(ex);
        assertEquals(400, ex.statusCode);
    }

    // --- Happy-path tests ---

    /** Stub QueryExecutor that returns empty lists for all calls. */
    private static QueryExecutor emptyExecutor() {
        return new QueryExecutor() {
            @Override
            public List<QueryResults.WordSketchResult> findCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) {
                return List.of();
            }
            @Override public List<QueryResults.ConcordanceResult> executeCqlQuery(String p, int m) { return List.of(); }
            @Override public List<QueryResults.CollocateResult> executeBcqlQuery(String p, int m) { return List.of(); }
            @Override public long getTotalFrequency(String lemma) { return 0; }
            @Override public List<QueryResults.WordSketchResult> executeSurfacePattern(
                    String lemma, String pattern, double minLogDice, int maxResults) { return List.of(); }
            @Override public List<QueryResults.WordSketchResult> executeDependencyPattern(
                    String lemma, String deprel, double minLogDice, int maxResults) { return List.of(); }
            @Override public List<QueryResults.WordSketchResult> executeDependencyPatternWithPos(
                    String lemma, String deprel,
                    double minLogDice, int maxResults, String headPosConstraint) { return List.of(); }
            @Override public void close() {}
        };
    }

    @Test
    void handleSketchRequest_validLemma_returns200() throws Exception {
        SketchHandlers handlers = new SketchHandlers(emptyExecutor(), GrammarConfigHelper.requireTestConfig());
        MockExchange ex = new MockExchange("http://localhost/api/sketch/house");
        handlers.handleSketchRequest(ex);
        assertEquals(200, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertEquals("house", body.getString("lemma"));
        assertTrue(body.containsKey("relations"), "Response must contain 'relations' key");
        assertNotNull(body.getJSONArray("relations"));
    }

    @Test
    void handleSemanticFieldExplore_validSeeds_returns200() throws Exception {
        QueryExecutor executor = emptyExecutor();
        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor);
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), explorer);
        MockExchange ex = new MockExchange(
                "http://localhost/api/semantic-field/explore-multi?seeds=theory,model&relation=noun_adj_predicates");
        handlers.handleSemanticFieldExploreMulti(ex);
        assertEquals(200, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertTrue(body.containsKey("seeds"), "Response must contain 'seeds' key");
        assertTrue(body.containsKey("discovered_nouns"), "Response must contain 'discovered_nouns' key");
        assertTrue(body.containsKey("core_collocates"), "Response must contain 'core_collocates' key");
    }

    @Test
    void handleSemanticFieldComparison_missingSeeds_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        MockExchange ex = new MockExchange("http://localhost/api/semantic-field");
        handlers.handleSemanticFieldComparison(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldComparison_validSeeds_returns200() throws Exception {
        QueryExecutor executor = emptyExecutor();
        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor);
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), explorer);
        MockExchange ex = new MockExchange(
                "http://localhost/api/semantic-field?seeds=theory,model&min_logdice=3.0");
        handlers.handleSemanticFieldComparison(ex);
        assertEquals(200, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertEquals("ok", body.getString("status"));
        assertTrue(body.containsKey("seeds"), "Response must contain 'seeds' key");
        assertTrue(body.containsKey("adjectives"), "Response must contain 'adjectives' key");
        assertTrue(body.containsKey("edges"), "Response must contain 'edges' key");
    }

    @Test
    void handleSemanticFieldComparison_invalidNumericParam_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        MockExchange ex = new MockExchange(
                "http://localhost/api/semantic-field?seeds=theory,model&min_logdice=notanumber");
        handlers.handleSemanticFieldComparison(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExamples_missingAdjective_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        MockExchange ex = new MockExchange("http://localhost/api/semantic-field/examples?noun=theory");
        handlers.handleSemanticFieldExamples(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExamples_missingNoun_returns400() throws Exception {
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), null);
        MockExchange ex = new MockExchange("http://localhost/api/semantic-field/examples?adjective=important");
        handlers.handleSemanticFieldExamples(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleSemanticFieldExamples_validParams_returns200() throws Exception {
        QueryExecutor executor = emptyExecutor();
        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor);
        ExplorationHandlers handlers = new ExplorationHandlers(GrammarConfigHelper.requireTestConfig(), explorer);
        MockExchange ex = new MockExchange(
                "http://localhost/api/semantic-field/examples?adjective=important&noun=theory&relation=noun_adj_predicates");
        handlers.handleSemanticFieldExamples(ex);
        assertEquals(200, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertEquals("ok", body.getString("status"));
        assertTrue(body.containsKey("examples"), "Response must contain 'examples' key");
        assertEquals("important", body.getString("adjective"));
        assertEquals("theory", body.getString("noun"));
    }

    @Test
    void handleVisualRadial_withValidJsonBody_returnsSvg() throws Exception {
        SketchHandlers handlers = new SketchHandlers(null, GrammarConfigHelper.requireTestConfig());
        String body = "{\"center\":\"theory\",\"width\":400,\"height\":300," +
                "\"items\":[{\"label\":\"elegant\",\"score\":7.5},{\"label\":\"modern\",\"score\":5.0}]}";
        MockPostBodyExchange ex = new MockPostBodyExchange("/api/visual/radial", body);
        handlers.handleVisualRadial(ex);
        assertEquals(200, ex.statusCode);
        String response = ex.getResponseBodyAsString();
        assertTrue(response.contains("<svg"), "Response must contain SVG element");
        assertTrue(response.contains("theory"), "Response must contain the center word");
    }

    static class MockPostBodyExchange extends MockExchange {
        private final byte[] requestBodyBytes;

        MockPostBodyExchange(String uriString, String body) {
            super(uriString);
            this.requestBodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override public InputStream getRequestBody() {
            return new java.io.ByteArrayInputStream(requestBodyBytes);
        }

        @Override public String getRequestMethod() { return "POST"; }
    }
}


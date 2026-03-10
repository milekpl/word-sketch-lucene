package pl.marcinmilkowski.word_sketch.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;

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
}

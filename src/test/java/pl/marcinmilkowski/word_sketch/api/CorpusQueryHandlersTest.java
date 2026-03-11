package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CorpusQueryHandlers} request validation:
 * body size limit, invalid JSON, missing/blank query, and pattern complexity.
 */
class CorpusQueryHandlersTest {

    /** MockExchange variant that accepts a configurable request body. */
    static class PostMockExchange extends HttpExchange {
        private final URI uri;
        private final byte[] requestBodyBytes;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        int statusCode = -1;

        PostMockExchange(String uriString, String body) {
            try { this.uri = new URI(uriString); } catch (Exception e) { throw new RuntimeException(e); }
            this.requestBodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        }

        @Override public URI getRequestURI() { return uri; }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public String getRequestMethod() { return "POST"; }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(requestBodyBytes); }
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

        String getResponseBodyAsString() { return responseBody.toString(StandardCharsets.UTF_8); }
    }

    private static PostMockExchange postExchange(String body) {
        return new PostMockExchange("http://localhost/api/bcql", body);
    }

    private static CorpusQueryHandlers handlers() {
        return new CorpusQueryHandlers(null);
    }

    @Test
    void handleCorpusQuery_bodyTooLarge_returns413() throws Exception {
        String oversizeBody = "x".repeat(65537);
        PostMockExchange ex = postExchange(oversizeBody);
        HttpApiUtils.wrapWithErrorHandling(handlers()::handleCorpusQuery, "test").handle(ex);
        assertEquals(413, ex.statusCode);
    }

    @Test
    void handleCorpusQuery_invalidJson_returns400() throws Exception {
        PostMockExchange ex = postExchange("not-json");
        HttpApiUtils.wrapWithErrorHandling(handlers()::handleCorpusQuery, "test").handle(ex);
        assertEquals(400, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertNotNull(body.getString("error"), "Error field must be present");
    }

    @Test
    void handleCorpusQuery_missingQueryField_returns400() throws Exception {
        PostMockExchange ex = postExchange("{\"top\": 10}");
        HttpApiUtils.wrapWithErrorHandling(handlers()::handleCorpusQuery, "test").handle(ex);
        assertEquals(400, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertTrue(body.getString("error").contains("query"), "Error should mention 'query'");
    }

    @Test
    void handleCorpusQuery_blankQueryField_returns400() throws Exception {
        PostMockExchange ex = postExchange("{\"query\": \"   \"}");
        HttpApiUtils.wrapWithErrorHandling(handlers()::handleCorpusQuery, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleCorpusQuery_bracketDepthExceeded_returns400() throws Exception {
        // Use a simple bracket pattern with no inner quotes so JSON stays valid
        String deepPattern = "[x]".repeat(21);
        PostMockExchange ex = postExchange("{\"query\": \"" + deepPattern + "\"}");
        HttpApiUtils.wrapWithErrorHandling(handlers()::handleCorpusQuery, "test").handle(ex);
        assertEquals(400, ex.statusCode);
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertTrue(body.getString("error").contains("complex"), "Error should mention pattern complexity");
    }
}

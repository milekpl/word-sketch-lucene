package pl.marcinmilkowski.word_sketch.api;

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
 * Tests for {@link VisualizationHandlers}: mode validation, body size limit,
 * invalid JSON, and successful radial render.
 */
class VisualizationHandlersTest {

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
        return new PostMockExchange("http://localhost/api/visual/radial", body);
    }

    private static VisualizationHandlers handlers() {
        return new VisualizationHandlers();
    }

    @Test
    void handleVisualRadial_unknownMode_throwsIAE() {
        PostMockExchange ex = postExchange("{\"center\":\"house\",\"mode\":\"unknown\",\"items\":[]}");
        assertThrows(IllegalArgumentException.class,
                () -> handlers().handleVisualRadial(ex));
    }

    @Test
    void handleVisualRadial_bodyTooLarge_throws413Exception() throws Exception {
        String oversizeBody = "x".repeat(65537);
        PostMockExchange ex = postExchange(oversizeBody);
        HttpApiUtils.wrapWithErrorHandling(handlers()::handleVisualRadial, "test").handle(ex);
        assertEquals(413, ex.statusCode);
    }

    @Test
    void handleVisualRadial_invalidJson_throwsIAE() {
        PostMockExchange ex = postExchange("not-valid-json{{");
        assertThrows(IllegalArgumentException.class,
                () -> handlers().handleVisualRadial(ex));
    }

    @Test
    void handleVisualRadial_validRequest_returns200WithSvg() throws Exception {
        String body = "{\"center\":\"house\",\"width\":400,\"height\":300,\"items\":[" +
                "{\"label\":\"big\",\"score\":8.5},{\"label\":\"old\",\"score\":7.2}]}";
        PostMockExchange ex = postExchange(body);
        handlers().handleVisualRadial(ex);
        assertEquals(200, ex.statusCode);
        String response = ex.getResponseBodyAsString();
        assertTrue(response.contains("<svg"), "Response should be SVG content");
    }
}

package pl.marcinmilkowski.word_sketch.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

/** Shared test utilities for HTTP handler tests. */
public class MockExchangeFactory {

    public static class MockExchange extends HttpExchange {
        private final URI uri;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        public int statusCode = -1;
        private long responseLength = -1;

        public MockExchange(String uriString) {
            try { this.uri = new URI(uriString); } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Override public URI getRequestURI() { return uri; }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        @Override public OutputStream getResponseBody() { return responseBody; }
        @Override public void sendResponseHeaders(int rCode, long responseLength) throws java.io.IOException {
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

        public String getResponseBodyAsString() { return responseBody.toString(); }
    }

    public static class MockPostBodyExchange extends MockExchange {
        private final byte[] requestBodyBytes;

        public MockPostBodyExchange(String uriString, String body) {
            super(uriString);
            this.requestBodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override public InputStream getRequestBody() {
            return new java.io.ByteArrayInputStream(requestBodyBytes);
        }

        @Override public String getRequestMethod() { return "POST"; }
    }
}

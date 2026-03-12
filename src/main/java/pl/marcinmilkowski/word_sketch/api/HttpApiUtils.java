package pl.marcinmilkowski.word_sketch.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import pl.marcinmilkowski.word_sketch.utils.JsonUtils;
import com.sun.net.httpserver.HttpExchange;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Static HTTP utility helpers for the REST API server.
 *
 * <p>This class intentionally co-locates two related HTTP concerns:
 * <ul>
 *   <li><strong>Response writing</strong>: {@link #sendJsonResponse}, {@link #sendError},
 *       {@link #sendOptionsResponse}, {@link #sendBinaryResponse}, and related CORS helpers.</li>
 *   <li><strong>Request parameter parsing</strong>: {@link #requireParam},
 *       {@link #parseIntParam}, {@link #parseDoubleParam}, {@link #parseQueryParams}.</li>
 * </ul>
 * Both sets of methods serve a single concern — handling the HTTP layer of the REST API — and
 * are used exclusively within the {@code api} package. Splitting them into two separate classes
 * would not reduce coupling (they share no state, only a package) and would add navigational
 * friction without architectural benefit. The class is package-private, so this design decision
 * does not affect the public API surface.</p>
 */
final class HttpApiUtils {

    private static final Logger logger = LoggerFactory.getLogger(HttpApiUtils.class);

    static final ObjectMapper MAPPER = JsonUtils.mapper();

    /**
     * Default allowed CORS origin (used when the {@code cors.allow.origin} system property
     * is absent). Override at startup via {@code -Dcors.allow.origin=https://myapp.example.com},
     * or in tests via {@code System.setProperty("cors.allow.origin", ...)}.
     */
    private static final String DEFAULT_CORS_ALLOW_ORIGIN = "http://localhost:3000";

    /**
     * Wraps a handler with uniform error handling: maps {@link IllegalArgumentException} to 400,
     * {@link java.io.IOException} to 500, and any other exception to 500.
     *
     * <p>Extracted here (from {@code WordSketchApiServer}) so the contract can be unit-tested
     * without standing up a full server.</p>
     */
    static com.sun.net.httpserver.HttpHandler wrapWithErrorHandling(
            com.sun.net.httpserver.HttpHandler handler, String description) {
        return exchange -> {
            try {
                handler.handle(exchange);
            } catch (RequestEntityTooLargeException e) {
                logger.warn("{} request too large", description, e);
                sendError(exchange, 413, e.getMessage());
            } catch (IllegalArgumentException e) {
                logger.warn("{} client error", description, e);
                sendError(exchange, 400, "Bad request: " + e.getMessage());
            } catch (java.io.IOException e) {
                logger.error("{} error", description, e);
                sendError(exchange, 500, description + " failed: " + e.getMessage());
            } catch (Exception e) {
                logger.error("{} unexpected error", description, e);
                sendError(exchange, 500, "Unexpected error: " + e.getMessage());
            }
        };
    }

    private HttpApiUtils() {}

    /**
     * Emits a one-time startup warning when the {@code cors.allow.origin} system property is set
     * to {@code *}. Call this once during server startup, before the first request is accepted,
     * so operators are alerted before any traffic is served.
     *
     * <p>A hard rejection is intentionally avoided: this is an internal research tool that may
     * legitimately run with wildcard CORS in a controlled environment. The warning ensures
     * operators are aware without breaking valid deployments.</p>
     */
    static void warnIfWildcardCors() {
        String origin = System.getProperty("cors.allow.origin", DEFAULT_CORS_ALLOW_ORIGIN);
        if ("*".equals(origin)) {
            logger.warn("CORS allow-origin is set to '*' — all origins permitted. "
                    + "Set -Dcors.allow.origin=https://your-app.example.com in production.");
        }
    }

    /**
     * Sets the {@code Access-Control-Allow-Origin} response header, reading {@code cors.allow.origin}
     * from the JVM system property at call time so per-test overrides take effect immediately.
     * Called by every response-sending method to ensure consistent CORS behaviour.
     */
    private static void setCorsHeader(HttpExchange exchange) {
        String origin = System.getProperty("cors.allow.origin", DEFAULT_CORS_ALLOW_ORIGIN);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
    }

    static void sendJsonResponse(@NonNull HttpExchange exchange, @NonNull Object data) throws IOException {
        String json = MAPPER.writeValueAsString(data);
        byte[] bytes = json.getBytes("UTF-8");

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        setCorsHeader(exchange);
        exchange.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void sendError(@NonNull HttpExchange exchange, int code, @NonNull String message) throws IOException {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("status", "error");
        error.put("error", message);
        String json = MAPPER.writeValueAsString(error);
        byte[] bytes = json.getBytes("UTF-8");

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        setCorsHeader(exchange);
        exchange.sendResponseHeaders(code, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Responds to an OPTIONS preflight request with the appropriate CORS headers and 204 No Content.
     */
    static void sendOptionsResponse(@NonNull HttpExchange exchange, @NonNull String allowedMethods) throws IOException {
        setCorsHeader(exchange);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", allowedMethods + ", OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.sendResponseHeaders(204, -1);
    }

    /**
     * Sends a binary response with the given content type (e.g., image/svg+xml) and CORS header.
     */
    static void sendBinaryResponse(@NonNull HttpExchange exchange, @NonNull String contentType, @NonNull byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        setCorsHeader(exchange);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Validates that the request uses the expected HTTP method.
     * If it does not, sends a 405 Method Not Allowed response and returns false.
     */
    static boolean requireMethod(@NonNull HttpExchange exchange, @NonNull String method) throws IOException {
        if (!method.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", method);
            sendError(exchange, 405, "Method Not Allowed");
            return false;
        }
        return true;
    }

    /** Maximum allowed length for a single query parameter value (200 characters). */
    static final int MAX_PARAM_LENGTH = 200;

    /** Maximum request body size in bytes accepted by request body endpoints (64 KB). */
    static final int MAX_REQUEST_BODY_BYTES = 65536;

    /**
     * Returns the parameter value, or throws {@link IllegalArgumentException} if missing, empty,
     * or exceeds {@link #MAX_PARAM_LENGTH} characters.
     * {@link #wrapWithErrorHandling} catches IAE and maps it to a 400 Bad Request response.
     */
    static @NonNull String requireParam(@NonNull Map<String, String> params, @NonNull String name) {
        String v = params.getOrDefault(name, "").trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException("Missing required parameter: " + name);
        }
        if (v.length() > MAX_PARAM_LENGTH) {
            throw new IllegalArgumentException(
                "Parameter '" + name + "' exceeds maximum length of " + MAX_PARAM_LENGTH + " characters");
        }
        return v;
    }

    /**
     * Parses an integer query parameter by name; uses {@code defaultValue} when the key is absent.
     * Throws {@link IllegalArgumentException} on parse failure so {@link #wrapWithErrorHandling}
     * catches it and sends a 400 Bad Request response.
     */
    static int parseIntParam(@NonNull Map<String, String> params, @NonNull String name, int defaultValue) {
        String raw = params.get(name);
        if (raw == null) return defaultValue;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid numeric parameter '" + name + "': expected integer, got: '" + raw + "'");
        }
    }

    /**
     * Parses a double query parameter by name; uses {@code defaultValue} when the key is absent.
     * Throws {@link IllegalArgumentException} on parse failure so {@link #wrapWithErrorHandling}
     * catches it and sends a 400 Bad Request response.
     */
    static double parseDoubleParam(@NonNull Map<String, String> params, @NonNull String name, double defaultValue) {
        String raw = params.get(name);
        if (raw == null) return defaultValue;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid numeric parameter '" + name + "': expected decimal, got: '" + raw + "'");
        }
    }

    /**
     * Parses a URL query string into a key-value parameter map.
     *
     * <p>Splits on {@code &} and {@code =}, decodes percent-encoded values using UTF-8,
     * and silently ignores parameter tokens with no {@code =} separator. Passing a
     * {@code null} or empty query returns an empty map.</p>
     *
     * @param query the raw query string (may be null or empty); percent-encoded characters are decoded
     * @return mutable map of decoded parameter names to decoded values; never null
     * @throws IllegalArgumentException if any key or value cannot be URL-decoded, so
     *         callers and {@link #wrapWithErrorHandling} can return a 400 Bad Request response rather than
     *         silently producing a malformed parameter map.
     */
    static @NonNull Map<String, String> parseQueryParams(@Nullable String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }

        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                try {
                    params.put(
                        java.net.URLDecoder.decode(keyValue[0], "UTF-8"),
                        java.net.URLDecoder.decode(keyValue[1], "UTF-8")
                    );
                } catch (java.io.UnsupportedEncodingException e) {
                    // UTF-8 is always supported; this branch is unreachable in practice.
                    throw new IllegalArgumentException("Unsupported encoding decoding parameter '" + keyValue[0] + "'", e);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Malformed URL encoding in parameter '" + keyValue[0] + "': " + e.getMessage(), e);
                }
            }
        }

        return params;
    }

    /**
     * Reads the request body up to {@link #MAX_REQUEST_BODY_BYTES} and parses it as a JSON object.
     *
     * @param exchange the HTTP exchange to read from
     * @return the parsed JSON object
     * @throws IOException                    if reading fails
     * @throws RequestEntityTooLargeException if the body exceeds {@link #MAX_REQUEST_BODY_BYTES}
     * @throws IllegalArgumentException       if the body is not valid JSON
     */
    static ObjectNode readJsonBody(HttpExchange exchange) throws IOException {
        String body = readBodyWithSizeLimit(exchange, MAX_REQUEST_BODY_BYTES);
        try {
            return MAPPER.readValue(body, ObjectNode.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON in request body: " + e.getMessage(), e);
        }
    }

    /**
     * Reads the full request body, throwing {@link RequestEntityTooLargeException} if it exceeds
     * {@code maxBytes}. Uses a one-byte-over read to detect oversize bodies without buffering the
     * entire stream.
     *
     * @param exchange  the HTTP exchange to read from
     * @param maxBytes  maximum allowed body size in bytes
     * @return the body as a UTF-8 string
     * @throws IOException                    if reading fails
     * @throws RequestEntityTooLargeException if the body exceeds {@code maxBytes}
     */
    static String readBodyWithSizeLimit(HttpExchange exchange, int maxBytes) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readNBytes(maxBytes + 1);
        if (bodyBytes.length > maxBytes) {
            throw new RequestEntityTooLargeException("Request body too large");
        }
        return new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}

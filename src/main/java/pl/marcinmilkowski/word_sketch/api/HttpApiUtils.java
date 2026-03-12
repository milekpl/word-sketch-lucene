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

/** HTTP helpers for the REST API: response writing and query parameter parsing. */
final class HttpApiUtils {

    private static final Logger logger = LoggerFactory.getLogger(HttpApiUtils.class);

    private static final ObjectMapper MAPPER = JsonUtils.mapper();

    /** Returns the shared Jackson {@link ObjectMapper}. Exposed for tests that need to parse response bodies. */
    static ObjectMapper mapper() { return MAPPER; }

    /**
     * Default allowed CORS origin (used when the {@code cors.allow.origin} system property
     * is absent). Override at startup via {@code -Dcors.allow.origin=https://myapp.example.com},
     * or in tests via {@code System.setProperty("cors.allow.origin", ...)}.
     */
    private static final String DEFAULT_CORS_ALLOW_ORIGIN = "http://localhost:3000";

    /**
     * Wraps a handler with tiered error handling:
     * <ul>
     *   <li>{@link RequestEntityTooLargeException} → 413</li>
     *   <li>{@link IllegalArgumentException} → 400 (validation / missing param)</li>
     *   <li>{@link com.fasterxml.jackson.core.JsonProcessingException} → 400 (malformed JSON input from client)</li>
     *   <li>{@link java.io.IOException} → 500 (index / I/O failure)</li>
     *   <li>Any other {@link Exception} → 500 (unexpected server error)</li>
     * </ul>
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
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // JsonProcessingException extends IOException; catch it before the IOException
                // handler so that malformed client JSON is reported as 400, not 500.
                logger.warn("{} client sent invalid JSON", description, e);
                sendError(exchange, 400, "Bad request: invalid JSON — " + e.getOriginalMessage());
            } catch (java.io.IOException e) {
                logger.error("{} error", description, e);
                sendError(exchange, 500, description + " failed: " + e.getMessage());
            } catch (NullPointerException | IllegalStateException e) {
                logger.error("{} internal error", description, e);
                sendError(exchange, 500, "Internal server error: " + e.getMessage());
            } catch (Exception e) {
                logger.error("{} unexpected error", description, e);
                sendError(exchange, 500, "Unexpected error: " + e.getMessage());
            }
        };
    }

    private HttpApiUtils() {}

    /** Warns at startup when {@code cors.allow.origin} is set to {@code *}. */
    static void warnIfWildcardCors() {
        String origin = System.getProperty("cors.allow.origin", DEFAULT_CORS_ALLOW_ORIGIN);
        if ("*".equals(origin)) {
            logger.warn("CORS allow-origin is set to '*' — all origins permitted. "
                    + "Set -Dcors.allow.origin=https://your-app.example.com in production.");
        }
    }

    /** Sets {@code Access-Control-Allow-Origin} from the {@code cors.allow.origin} system property. */
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

    /** Returns the parameter value, or throws {@link IllegalArgumentException} if missing, empty, or over {@link #MAX_PARAM_LENGTH} chars. */
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

    /** Parses an integer query parameter; returns {@code defaultValue} when absent, throws {@link IllegalArgumentException} on bad input. */
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

    /** Parses a double query parameter; returns {@code defaultValue} when absent, throws {@link IllegalArgumentException} on bad input. */
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
     * Parses a URL query string into a decoded key-value map; returns an empty map for null/empty input.
     *
     * @param query raw query string (may be null); percent-encoded characters are decoded
     * @return mutable map of decoded names to values; never null
     * @throws IllegalArgumentException if a key or value cannot be URL-decoded
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

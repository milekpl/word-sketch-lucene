package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
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
class HttpApiUtils {

    private static final Logger logger = LoggerFactory.getLogger(HttpApiUtils.class);

    /**
     * Allowed CORS origin. Read at use time via {@link #getCorsAllowOrigin()} so that
     * the system property is evaluated when the response is sent, not at class-load time.
     * Override at runtime via the {@code cors.allow.origin} JVM system property:
     * {@code -Dcors.allow.origin=https://myapp.example.com}
     */
    private static String getCorsAllowOrigin() {
        return System.getProperty("cors.allow.origin", "http://localhost:3000");
    }

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
     * Sets the {@code Access-Control-Allow-Origin} response header to {@link #CORS_ALLOW_ORIGIN}.
     * Called by every response-sending method to ensure consistent CORS behaviour.
     */
    private static void setCorsHeader(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", getCorsAllowOrigin());
    }

    public static void sendJsonResponse(HttpExchange exchange, Object data) throws IOException {
        String json = JSON.toJSONString(data);
        byte[] bytes = json.getBytes("UTF-8");

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        setCorsHeader(exchange);
        exchange.sendResponseHeaders(200, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void sendError(HttpExchange exchange, int code, String message) throws IOException {
        JSONObject error = new JSONObject();
        error.put("error", message);
        String json = JSON.toJSONString(error);
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
    public static void sendOptionsResponse(HttpExchange exchange, String allowedMethods) throws IOException {
        setCorsHeader(exchange);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", allowedMethods + ", OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.sendResponseHeaders(204, -1);
    }

    /**
     * Sends a binary response with the given content type (e.g., image/svg+xml) and CORS header.
     */
    public static void sendBinaryResponse(HttpExchange exchange, String contentType, byte[] bytes) throws IOException {
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
    public static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (!method.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", method);
            sendError(exchange, 405, "Method Not Allowed");
            return false;
        }
        return true;
    }

    /**
     * Returns the parameter value, or throws {@link IllegalArgumentException} if missing or empty.
     * {@link #wrapWithErrorHandling} catches IAE and maps it to a 400 Bad Request response.
     */
    public static String requireParam(Map<String, String> params, String name) {
        String v = params.getOrDefault(name, "").trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException("Missing required parameter: " + name);
        }
        return v;
    }

    /**
     * Parses an integer query parameter by name; uses {@code defaultValue} when the key is absent.
     * Throws {@link IllegalArgumentException} on parse failure so {@link #wrapWithErrorHandling}
     * catches it and sends a 400 Bad Request response.
     */
    static int parseIntParam(Map<String, String> params, String name, int defaultValue) {
        String raw = params.getOrDefault(name, String.valueOf(defaultValue));
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
    static double parseDoubleParam(Map<String, String> params, String name, double defaultValue) {
        String raw = params.getOrDefault(name, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid numeric parameter '" + name + "': expected decimal, got: '" + raw + "'");
        }
    }

    /**
     *
     * @throws IllegalArgumentException if any key or value cannot be URL-decoded, so
     *         callers and {@code wrapHandler} can return a 400 Bad Request response rather than
     *         silently producing a malformed parameter map.
     */
    public static Map<String, String> parseQueryParams(String query) {
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
}

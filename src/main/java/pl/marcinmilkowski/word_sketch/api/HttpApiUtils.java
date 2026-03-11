package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Static HTTP utility helpers for the REST API server.
 */
class HttpApiUtils {

    private static final Logger logger = LoggerFactory.getLogger(HttpApiUtils.class);

    /**
     * Allowed CORS origin for development. Restrict to specific origins in production.
     * Change this value (or make it configurable) when deploying to a non-localhost environment.
     */
    static final String CORS_ALLOW_ORIGIN = "http://localhost:3000";

    private HttpApiUtils() {}

    public static void sendJsonResponse(HttpExchange exchange, Object data) throws IOException {
        String json = JSON.toJSONString(data);
        byte[] bytes = json.getBytes("UTF-8");

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", CORS_ALLOW_ORIGIN);
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
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", CORS_ALLOW_ORIGIN);
        exchange.sendResponseHeaders(code, bytes.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Responds to an OPTIONS preflight request with the appropriate CORS headers and 204 No Content.
     */
    public static void sendOptionsResponse(HttpExchange exchange, String allowedMethods) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", CORS_ALLOW_ORIGIN);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", allowedMethods + ", OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.sendResponseHeaders(204, -1);
    }

    /**
     * Sends a binary response with the given content type (e.g., image/svg+xml) and CORS header.
     */
    public static void sendBinaryResponse(HttpExchange exchange, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", CORS_ALLOW_ORIGIN);
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
     * Returns the parameter value, or sends a 400 error and returns null if missing/empty.
     * Caller must check for null return.
     */
    public static @Nullable String requireParam(HttpExchange exchange, Map<String, String> params, String name) throws IOException {
        String v = params.getOrDefault(name, "").trim();
        if (v.isEmpty()) {
            sendError(exchange, 400, "Missing required parameter: " + name);
            return null;
        }
        return v;
    }

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
                } catch (Exception e) {
                    logger.warn("Failed to decode query parameter '{}': {}", pair, e.getMessage(), e);
                }
            }
        }

        return params;
    }
}

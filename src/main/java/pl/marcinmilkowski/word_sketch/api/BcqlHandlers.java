package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONException;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP handler for arbitrary BCQL query endpoints.
 * Extracted from {@link SketchHandlers} to separate BCQL query concerns from word-sketch concerns.
 */
class BcqlHandlers {

    private static final Logger logger = LoggerFactory.getLogger(BcqlHandlers.class);

    private static final int MAX_REQUEST_BODY_BYTES = 65536;

    /** Maximum length (chars) accepted for a BCQL pattern. */
    private static final int MAX_BCQL_PATTERN_LENGTH = 1024;

    /** Maximum number of {@code [} bracket tokens accepted in a BCQL pattern (complexity limit). */
    private static final int MAX_BCQL_BRACKET_DEPTH = 20;

    private final QueryExecutor executor;

    BcqlHandlers(QueryExecutor executor) {
        this.executor = executor;
    }

    /**
     * Handle arbitrary BCQL query (POST with JSON body to avoid URL encoding issues).
     * POST /api/bcql with body: {"query": "[lemma=\"test\"]", "top": 20}
     * The deprecated "limit" field is also accepted for backward compatibility.
     */
    void handleBcqlQueryPost(HttpExchange exchange) throws IOException {
        BcqlRequest req = parseBcqlRequest(exchange);
        if (req == null) return;

        logger.debug("BCQL query: {}", req.query());

        List<QueryResults.CollocateResult> results = executor.executeBcqlQuery(req.query(), req.top());
        HttpApiUtils.sendJsonResponse(exchange, buildBcqlResponse(req, results));
    }

    private record BcqlRequest(String query, int top) {}

    /** Parse and validate the BCQL POST body; returns null and sends an error if invalid. */
    private BcqlRequest parseBcqlRequest(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (bodyBytes.length > MAX_REQUEST_BODY_BYTES) {
            HttpApiUtils.sendError(exchange, 413, "Request body too large");
            return null;
        }
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        JSONObject obj;
        try {
            obj = JSON.parseObject(body);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid JSON in request body: " + e.getMessage(), e);
        }
        String bcqlQuery = obj.getString("query");
        if (bcqlQuery == null || bcqlQuery.isBlank()) {
            HttpApiUtils.sendError(exchange, 400, "Missing required parameter: query");
            return null;
        }
        if (bcqlQuery.length() > MAX_BCQL_PATTERN_LENGTH) {
            HttpApiUtils.sendError(exchange, 400,
                    "Pattern too long: " + bcqlQuery.length() + " chars (max " + MAX_BCQL_PATTERN_LENGTH + ")");
            return null;
        }
        long bracketCount = bcqlQuery.chars().filter(c -> c == '[').count();
        if (bracketCount > MAX_BCQL_BRACKET_DEPTH) {
            HttpApiUtils.sendError(exchange, 400,
                    "Pattern too complex: " + bracketCount + " token constraints (max " + MAX_BCQL_BRACKET_DEPTH + ")");
            return null;
        }
        // Accept 'top' as the canonical parameter; 'limit' retained as deprecated backward-compat alias
        Integer top = obj.get("top") != null ? obj.getIntValue("top") : null;
        if (top == null) top = obj.get("limit") != null ? obj.getIntValue("limit") : null;
        int resolvedTop = (top != null && top > 0) ? top : 10;
        return new BcqlRequest(bcqlQuery, resolvedTop);
    }

    /** Build the BCQL query JSON response map from the parsed request and results. */
    private static Map<String, Object> buildBcqlResponse(BcqlRequest req, List<QueryResults.CollocateResult> results) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("query", req.query());
        response.put("total_results", results.size());
        response.put("top", req.top());

        List<Map<String, Object>> resultsList = new ArrayList<>();
        for (QueryResults.CollocateResult r : results) {
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("sentence", r.getSentence());
            resultMap.put("raw", r.rawXml() != null ? r.rawXml() : "");
            resultMap.put("match_start", r.getStartOffset());
            resultMap.put("match_end", r.getEndOffset());
            resultMap.put("collocate_lemma", r.collocateLemma() != null ? r.collocateLemma() : "");
            resultMap.put("frequency", r.frequency());
            resultMap.put("log_dice", r.logDice());
            resultsList.add(resultMap);
        }
        response.put("results", resultsList);
        return response;
    }
}

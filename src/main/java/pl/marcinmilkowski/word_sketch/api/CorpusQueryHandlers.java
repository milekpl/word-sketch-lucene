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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP handler for arbitrary corpus query endpoints.
 * Extracted from {@link SketchHandlers} to separate corpus query concerns from word-sketch concerns.
 */
class CorpusQueryHandlers {

    private static final Logger logger = LoggerFactory.getLogger(CorpusQueryHandlers.class);

    private static final int MAX_REQUEST_BODY_BYTES = 65536;

    /** Maximum length (chars) accepted for a BCQL pattern. */
    private static final int MAX_BCQL_PATTERN_LENGTH = 1024;

    /** Maximum number of {@code [} bracket tokens accepted in a BCQL pattern (complexity limit). */
    private static final int MAX_BCQL_BRACKET_DEPTH = 20;

    private final QueryExecutor executor;

    CorpusQueryHandlers(QueryExecutor executor) {
        this.executor = executor;
    }

    /**
     * Handle arbitrary BCQL query (POST with JSON body to avoid URL encoding issues).
     * POST /api/bcql with body: {"query": "[lemma=\"test\"]", "top": 20}
     * The {@code top} field controls the maximum number of results returned.
     */
    void handleCorpusQuery(HttpExchange exchange) throws IOException {
        BcqlRequest req = parseBcqlRequest(exchange);

        logger.debug("BCQL query: {}", req.query());

        List<QueryResults.CollocateResult> results = executor.executeBcqlQuery(req.query(), req.top());
        HttpApiUtils.sendJsonResponse(exchange, buildBcqlResponse(req, results));
    }

    private record BcqlRequest(String query, int top) {}

    /**
     * Parse and validate the BCQL POST body.
     * Throws {@link IllegalArgumentException} for 400-class validation failures (missing/blank
     * query, pattern too long, pattern too complex); {@code wrapWithErrorHandling} maps these to
     * 400 responses automatically.
     * Throws {@link RequestEntityTooLargeException} when the body exceeds the size limit;
     * {@code wrapWithErrorHandling} maps this to HTTP 413.
     */
    private BcqlRequest parseBcqlRequest(HttpExchange exchange) throws IOException {
        String body = HttpApiUtils.readBodyWithSizeLimit(exchange, MAX_REQUEST_BODY_BYTES);
        JSONObject obj;
        try {
            obj = JSON.parseObject(body);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid JSON in request body: " + e.getMessage(), e);
        }
        String bcqlQuery = obj.getString("query");
        if (bcqlQuery == null || bcqlQuery.isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: query");
        }
        if (bcqlQuery.length() > MAX_BCQL_PATTERN_LENGTH) {
            throw new IllegalArgumentException(
                    "Pattern too long: " + bcqlQuery.length() + " chars (max " + MAX_BCQL_PATTERN_LENGTH + ")");
        }
        long bracketCount = bcqlQuery.chars().filter(c -> c == '[').count();
        if (bracketCount > MAX_BCQL_BRACKET_DEPTH) {
            throw new IllegalArgumentException(
                    "Pattern too complex: " + bracketCount + " token constraints (max " + MAX_BCQL_BRACKET_DEPTH + ")");
        }
        int resolvedTop = Optional.ofNullable(obj.getInteger("top"))
                .filter(v -> v > 0)
                .orElse(10);
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
            resultMap.put("sentence", r.sentence());
            resultMap.put("raw", r.rawXml() != null ? r.rawXml() : "");
            resultMap.put("match_start", r.startOffset());
            resultMap.put("match_end", r.endOffset());
            resultMap.put("collocate_lemma", r.collocateLemma() != null ? r.collocateLemma() : "");
            resultMap.put("frequency", r.frequency());
            resultMap.put("log_dice", r.logDice());
            resultsList.add(resultMap);
        }
        response.put("results", resultsList);
        return response;
    }
}

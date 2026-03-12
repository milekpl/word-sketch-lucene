package pl.marcinmilkowski.word_sketch.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP handler for arbitrary corpus query endpoints.
 * Extracted from {@link SketchHandlers} to separate corpus query concerns from word-sketch concerns.
 */
class CorpusQueryHandlers {

    private static final Logger logger = LoggerFactory.getLogger(CorpusQueryHandlers.class);

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
        String body = HttpApiUtils.readBodyWithSizeLimit(exchange, HttpApiUtils.MAX_REQUEST_BODY_BYTES);
        ObjectNode obj;
        try {
            obj = HttpApiUtils.MAPPER.readValue(body, ObjectNode.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON in request body: " + e.getMessage(), e);
        }
        String bcqlQuery = obj.path("query").textValue();
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
        com.fasterxml.jackson.databind.JsonNode topNode = obj.path("top");
        int resolvedTop = (!topNode.isMissingNode() && !topNode.isNull() && topNode.asInt() > 0)
                ? topNode.asInt()
                : 10;
        return new BcqlRequest(bcqlQuery, resolvedTop);
    }

    /** Build the BCQL query JSON response map from the parsed request and results. */
    private static Map<String, Object> buildBcqlResponse(BcqlRequest req, List<QueryResults.CollocateResult> results) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("query", req.query());
        response.put("total_results", results.size());
        response.put("top", req.top());
        response.put("results", results.stream().map(ExploreResponseAssembler::collocateToFullResultMap).toList());
        return response;
    }
}

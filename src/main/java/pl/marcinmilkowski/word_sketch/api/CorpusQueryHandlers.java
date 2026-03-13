package pl.marcinmilkowski.word_sketch.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.model.sketch.*;
import pl.marcinmilkowski.word_sketch.query.CollocateQueryPort;

import java.io.IOException;
import java.util.List;

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

    private final CollocateQueryPort executor;

    CorpusQueryHandlers(CollocateQueryPort executor) {
        this.executor = executor;
    }

    /**
     * Handle arbitrary BCQL query (POST with JSON body to avoid URL encoding issues).
     * POST /api/bcql with body: {"query": "[lemma=\"test\"]", "top": 20}
     * The {@code top} field controls the maximum number of results returned.
     */
    void handleCorpusQuery(HttpExchange exchange) throws IOException {
        BcqlRequest req = parseBcqlRequest(exchange);

        logger.info("BCQL audit – src={} len={} query: {}",
                exchange.getRemoteAddress(), req.query().length(), req.query());

        List<CollocateResult> results = executor.executeBcqlQuery(req.query(), req.top());
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
        ObjectNode obj = HttpApiUtils.readJsonBody(exchange);
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

    /** Typed response envelope for the BCQL query endpoint. Serialised to JSON by Jackson. */
    private record BcqlQueryResponse(
            String status,
            String query,
            int total_results,
            int top,
            List<BcqlResultEntry> results) {}

    /**
     * Typed representation of a single BCQL result row.
     * Replaces the raw {@code Map<String,Object>} previously produced by
     * {@code ExploreResponseAssembler.collocateResultToFullMap}.
     */
    private record BcqlResultEntry(
            String sentence,
            String raw,
            int match_start,
            int match_end,
            String collocate_lemma,
            long frequency,
            double log_dice) {}

    /** Build the typed BCQL query response from the parsed request and results. */
    private static BcqlQueryResponse buildBcqlResponse(BcqlRequest req, List<CollocateResult> results) {
        return new BcqlQueryResponse(
            "ok",
            req.query(),
            results.size(),
            req.top(),
            results.stream().map(CorpusQueryHandlers::toBcqlResultEntry).toList());
    }

    private static BcqlResultEntry toBcqlResultEntry(CollocateResult r) {
        return new BcqlResultEntry(
                r.sentence(),
                r.rawXml() != null ? r.rawXml() : "",
                r.startOffset(),
                r.endOffset(),
                r.collocateLemma() != null ? r.collocateLemma() : "",
                r.frequency(),
                r.logDice());
    }
}

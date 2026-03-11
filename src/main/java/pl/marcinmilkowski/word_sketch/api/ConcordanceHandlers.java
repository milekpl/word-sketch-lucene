package pl.marcinmilkowski.word_sketch.api;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.utils.CqlUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP handler for concordance (KWIC) endpoints.
 * Extracted from {@link SketchHandlers} to separate concordance concerns from word-sketch concerns.
 */
class ConcordanceHandlers {

    private static final Logger logger = LoggerFactory.getLogger(ConcordanceHandlers.class);

    private final QueryExecutor executor;
    private final GrammarConfig grammarConfig;

    ConcordanceHandlers(QueryExecutor executor, GrammarConfig grammarConfig) {
        this.executor = executor;
        this.grammarConfig = grammarConfig;
    }

    /**
     * Handle concordance examples.
     * GET /api/concordance/examples?word1=theory&word2=good&relation=noun_adj_predicates&top=10
     * Uses BCQL pattern from relations.json for the specified relation.
     */
    void handleConcordanceExamples(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HttpApiUtils.parseQueryParams(query);

        String noun = HttpApiUtils.requireParam(exchange, params, "word1");
        if (noun == null) return;
        String adjective = HttpApiUtils.requireParam(exchange, params, "word2");
        if (adjective == null) return;
        String relation = params.getOrDefault("relation", "noun_adj_predicates");

        int top;
        try {
            top = Integer.parseInt(params.getOrDefault("top", "10"));
        } catch (NumberFormatException e) {
            HttpApiUtils.sendError(exchange, 400, "Invalid numeric parameter: top");
            return;
        }

        String bcqlQuery = null;
        var rel = grammarConfig.getRelation(relation);
        if (rel.isPresent()) {
            String patternWithHead = rel.get().buildFullPattern(noun);
            bcqlQuery = CqlUtils.substituteAtPosition(patternWithHead, adjective, rel.get().collocatePosition());
        }

        boolean fallback = false;
        if (bcqlQuery == null || bcqlQuery.isEmpty()) {
            bcqlQuery = String.format("\"%s\" []{0,5} \"%s\"",
                noun.toLowerCase(), adjective.toLowerCase());
            fallback = true;
            logger.warn("Relation '{}' not resolved to a BCQL pattern; using proximity fallback: {}", relation, bcqlQuery);
        }

        List<QueryResults.CollocateResult> results = executor.executeBcqlQuery(bcqlQuery, top);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("word1", noun);
        response.put("word2", adjective);
        response.put("relation", relation);
        response.put("bcql", bcqlQuery);
        response.put("fallback", fallback);
        response.put("top_requested", top);
        response.put("total_results", results.size());

        List<Map<String, Object>> examplesList = new ArrayList<>();
        for (QueryResults.CollocateResult r : results) {
            Map<String, Object> exMap = new HashMap<>();
            exMap.put("sentence", r.getSentence());
            exMap.put("raw", r.rawXml() != null ? r.rawXml() : "");
            examplesList.add(exMap);
        }
        response.put("examples", examplesList);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }
}

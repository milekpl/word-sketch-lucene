package pl.marcinmilkowski.word_sketch.api;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.RelationPatternBuilder;
import pl.marcinmilkowski.word_sketch.query.QueryResults;
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
     *
     * <p>GET /api/concordance/examples?seed=theory&amp;collocate=good&amp;relation=noun_adj_predicates&amp;top=10</p>
     *
     * <p>Parameters:
     * <ul>
     *   <li>{@code seed} — headword (required); formerly {@code word1}</li>
     *   <li>{@code collocate} — the collocate word form to look up (required); formerly {@code word2}</li>
     *   <li>{@code relation} — grammar relation ID (default: noun_adj_predicates)</li>
     *   <li>{@code top} — max results (default: 10)</li>
     * </ul>
     * Uses BCQL pattern from relations.json for the specified relation.
     */
    void handleConcordanceExamples(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HttpApiUtils.parseQueryParams(query);

        String noun = HttpApiUtils.requireParam(params, "seed");
        String adjective = HttpApiUtils.requireParam(params, "collocate");
        String relation = params.getOrDefault("relation", "noun_adj_predicates");

        int top = HttpApiUtils.parseIntParam(params, "top", 10);

        String bcqlQuery = null;
        var rel = grammarConfig.getRelation(relation);
        if (rel.isPresent()) {
            String patternWithHead = RelationPatternBuilder.buildFullPattern(rel.get(), noun);
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
        response.put("seed", noun);
        response.put("collocate", adjective);
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

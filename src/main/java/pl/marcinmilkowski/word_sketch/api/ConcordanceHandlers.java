package pl.marcinmilkowski.word_sketch.api;

import com.sun.net.httpserver.HttpExchange;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.RelationPatternUtils;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.config.RelationUtils;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

import java.io.IOException;
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

    ConcordanceHandlers(QueryExecutor executor, @NonNull GrammarConfig grammarConfig) {
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
     * When the relation is not found, falls back to a proximity pattern and logs a warning;
     * the response includes a {@code fallback: true} field to signal this to callers.
     */
    void handleConcordanceExamples(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HttpApiUtils.parseQueryParams(query);
        ConcordanceExamplesRequest req = parseConcordanceExamplesRequest(params);

        var rel = grammarConfig.relation(req.relation());
        boolean fallback = rel.isEmpty();
        List<QueryResults.CollocateResult> results;
        String bcqlQuery;
        if (!fallback) {
            results = executor.executeBcqlQuery(
                RelationPatternUtils.buildFullPattern(rel.get(), req.seed(), req.collocate()), req.top());
            bcqlQuery = RelationPatternUtils.buildFullPattern(rel.get(), req.seed(), req.collocate());
        } else {
            bcqlQuery = String.format("\"%s\" []{0,5} \"%s\"", req.seed().toLowerCase(), req.collocate().toLowerCase());
            logger.warn("Relation '{}' not resolved to a BCQL pattern; using proximity fallback: {}", req.relation(), bcqlQuery);
            results = executor.executeBcqlQuery(bcqlQuery, req.top());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("seed", req.seed());
        response.put("collocate", req.collocate());
        response.put("relation", req.relation());
        response.put("bcql", bcqlQuery);
        response.put("top", req.top());
        response.put("total_results", results.size());
        if (fallback) {
            response.put("fallback", true);
        }

        response.put("examples", results.stream().map(ExploreResponseAssembler::collocateResultToExampleMap).toList());

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Parsed and validated request parameters for {@link #handleConcordanceExamples}.
     * Field names use the system-wide vocabulary ({@code seed}, {@code collocate}).
     */
    private record ConcordanceExamplesRequest(String seed, String collocate, String relation, int top) {}

    private ConcordanceExamplesRequest parseConcordanceExamplesRequest(Map<String, String> params) {
        String seed = HttpApiUtils.requireParam(params, "seed");
        String collocate = HttpApiUtils.requireParam(params, "collocate");
        String relation = RelationUtils.resolveRelationAlias(
            params.getOrDefault("relation", "noun_adj_predicates"));
        int top = HttpApiUtils.parseIntParam(params, "top", 10);
        return new ConcordanceExamplesRequest(seed, collocate, relation, top);
    }
}

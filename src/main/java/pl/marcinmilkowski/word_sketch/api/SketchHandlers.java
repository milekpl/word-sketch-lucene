package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader;
import pl.marcinmilkowski.word_sketch.config.RelationType;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.QueryResults;
import pl.marcinmilkowski.word_sketch.utils.PatternSubstitution;
import pl.marcinmilkowski.word_sketch.viz.RadialPlot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP handlers for word sketch, concordance, BCQL, and radial-plot endpoints.
 */
class SketchHandlers {

    private static final Logger logger = LoggerFactory.getLogger(SketchHandlers.class);

    private final QueryExecutor executor;
    private final GrammarConfigLoader grammarConfig;

    SketchHandlers(QueryExecutor executor, GrammarConfigLoader grammarConfig) {
        this.executor = executor;
        this.grammarConfig = grammarConfig;
    }

    void handleSketchRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.substring("/api/sketch/".length()).split("/");

        if (parts.length == 0 || parts[0].isEmpty()) {
            HttpApiUtils.sendError(exchange, 400, "Lemma required");
            return;
        }

        String lemma = parts[0];

        if (parts.length > 1 && "dep".equals(parts[1])) {
            String specificDeprel = parts.length > 2 ? parts[2] : null;
            try {
                if (specificDeprel != null && !specificDeprel.isEmpty()) {
                    handleDependencyRelationQuery(exchange, lemma, specificDeprel);
                } else {
                    handleFullDependencySketch(exchange, lemma);
                }
            } catch (IOException e) {
                logger.error("Dependency sketch error", e);
                HttpApiUtils.sendError(exchange, 500, "Dependency sketch failed: " + e.getMessage());
            }
            return;
        }

        String relation = parts.length > 1 ? parts[1] : null;
        try {
            if (relation != null && !relation.isEmpty()) {
                handleRelationQuery(exchange, lemma, relation);
            } else {
                handleFullSketch(exchange, lemma);
            }
        } catch (IOException e) {
            logger.error("Query error", e);
            HttpApiUtils.sendError(exchange, 500, "Query failed: " + e.getMessage());
        }
    }

    void handleSurfaceRelations(HttpExchange exchange) throws IOException {
        handleRelationsImpl(exchange, RelationType.SURFACE);
    }

    void handleDepRelations(HttpExchange exchange) throws IOException {
        handleRelationsImpl(exchange, RelationType.DEP);
    }

    private void handleRelationsImpl(HttpExchange exchange, RelationType relationType) throws IOException {
        JSONArray relationsArray = new JSONArray();
        if (grammarConfig != null) {
            for (var rel : grammarConfig.getRelations()) {
                if (rel.relationType() == relationType) {
                    Map<String, Object> obj = new HashMap<>();
                    obj.put("id", rel.id());
                    obj.put("name", rel.name());
                    obj.put("description", rel.description());
                    obj.put("relation_type", rel.relationType().name());
                    obj.put("pattern", rel.pattern());
                    if (relationType == RelationType.DEP) {
                        obj.put("deprel", rel.getDeprel());
                    }
                    relationsArray.add(obj);
                }
            }
        } else {
            HttpApiUtils.sendError(exchange, 500, "Grammar configuration not loaded");
            return;
        }
        HttpApiUtils.sendJsonResponse(exchange, Collections.singletonMap("relations", relationsArray));
    }

    private void handleFullSketch(HttpExchange exchange, String lemma) throws IOException {
        handleFullSketchImpl(exchange, lemma, RelationType.SURFACE);
    }

    private void handleRelationQuery(HttpExchange exchange, String lemma, String relation) throws IOException {
        handleRelationQueryInternal(exchange, lemma, relation, RelationType.SURFACE);
    }

    /**
     * Handle full dependency sketch request.
     * Returns all dependency relations for the given lemma.
     * DEP relations use surface patterns with [deprel="..."] constraints.
     */
    private void handleFullDependencySketch(HttpExchange exchange, String lemma) throws IOException {
        handleFullSketchImpl(exchange, lemma, RelationType.DEP);
    }

    private void handleFullSketchImpl(HttpExchange exchange, String lemma, RelationType relationType) throws IOException {
        boolean isDep = relationType == RelationType.DEP;
        Map<String, Object> byRelation = new HashMap<>();

        if (grammarConfig != null) {
            for (var rel : grammarConfig.getRelations()) {
                if (rel.relationType() == relationType) {
                    try {
                        if (isDep && rel.getDeprel() == null) continue;

                        String fullPattern = rel.getFullPattern(lemma);
                        List<QueryResults.WordSketchResult> results =
                            executor.executeSurfacePattern(
                                lemma, fullPattern,
                                rel.headPosition(), rel.collocatePosition(),
                                0.0, 20);

                        if (!results.isEmpty()) {
                            List<Map<String, Object>> collocations = new ArrayList<>();
                            for (QueryResults.WordSketchResult result : results) {
                                collocations.add(formatWordSketchResult(result));
                            }

                            Map<String, Object> relData = new HashMap<>();
                            if (isDep) {
                                relData.put("id", rel.id());
                                relData.put("name", rel.name());
                                relData.put("description", rel.description());
                                relData.put("deprel", rel.getDeprel());
                                relData.put("total_matches", results.stream().mapToInt(r -> (int) r.getFrequency()).sum());
                            } else {
                                relData.put("name", rel.name());
                                relData.put("cql", rel.pattern());
                                relData.put("collocate_pos_group", rel.collocatePosGroup().getValue());
                            }
                            relData.put("collocations", collocations);
                            byRelation.put(rel.id(), relData);
                        }
                    } catch (Exception e) {
                        logger.warn("Relation {} failed for lemma {}", rel.id(), lemma, e);
                    }
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("lemma", lemma);
        if (isDep) {
            response.put("type", "dependency");
            response.put("relations", byRelation);
        } else {
            response.put("patterns", byRelation);
        }
        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Handle specific dependency relation query.
     * Returns collocates for a single dependency relation.
     * DEP relations use surface patterns with [deprel="..."] constraints.
     */
    private void handleDependencyRelationQuery(HttpExchange exchange, String lemma, String relationId) throws IOException {
        handleRelationQueryInternal(exchange, lemma, relationId, RelationType.DEP);
    }

    private void handleRelationQueryInternal(HttpExchange exchange, String lemma, String relationId, RelationType relationType) throws IOException {
        if (grammarConfig == null) {
            HttpApiUtils.sendError(exchange, 500, "Grammar configuration not loaded");
            return;
        }
        var rel = grammarConfig.getRelation(relationId).orElse(null);
        if (rel == null || rel.relationType() != relationType) {
            HttpApiUtils.sendError(exchange, 400, "Unknown relation: " + relationId);
            return;
        }

        List<QueryResults.WordSketchResult> results;
        try {
            String fullPattern = rel.getFullPattern(lemma);
            results = executor.executeSurfacePattern(
                lemma, fullPattern,
                rel.headPosition(), rel.collocatePosition(),
                0.0, 50);
        } catch (IOException e) {
            logger.error("Query failed", e);
            HttpApiUtils.sendError(exchange, 500, "Query failed: " + e.getMessage());
            return;
        }

        List<Map<String, Object>> collocations = new ArrayList<>();
        for (QueryResults.WordSketchResult result : results) {
            collocations.add(formatWordSketchResult(result));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("lemma", lemma);
        response.put("relation", relationId);
        response.put("collocations", collocations);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    private static Map<String, Object> formatWordSketchResult(QueryResults.WordSketchResult result) {
        Map<String, Object> word = new HashMap<>();
        word.put("lemma", result.getLemma());
        word.put("frequency", result.getFrequency());
        word.put("log_dice", result.getLogDice());
        word.put("pos", result.getPos());
        return word;
    }

    /**
     * Handle concordance examples.
     * GET /api/concordance/examples?word1=theory&word2=good&relation=noun_adj_predicates&limit=10
     * Uses BCQL pattern from relations.json for the specified relation.
     */
    void handleConcordanceExamples(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HttpApiUtils.parseQueryParams(query);

        String word1 = HttpApiUtils.requireParam(exchange, params, "word1");
        if (word1 == null) return;
        String word2 = HttpApiUtils.requireParam(exchange, params, "word2");
        if (word2 == null) return;
        String relation = params.getOrDefault("relation", "noun_adj_predicates");

        int limit;
        try {
            limit = Integer.parseInt(params.getOrDefault("limit", "10"));
        } catch (NumberFormatException e) {
            HttpApiUtils.sendError(exchange, 400, "Invalid numeric parameter: limit");
            return;
        }

        String bcqlQuery = null;
        if (grammarConfig != null) {
            var rel = grammarConfig.getRelation(relation);
            if (rel.isPresent()) {
                String patternWithHead = rel.get().getFullPattern(word1);
                logger.debug("After getFullPattern: {}", patternWithHead);
                logger.debug("collocatePosition = {}", rel.get().collocatePosition());
                bcqlQuery = PatternSubstitution.substituteCollocate(patternWithHead, word2, rel.get().collocatePosition());
                logger.debug("After substituteCollocate: {}", bcqlQuery);
            } else {
                logger.debug("Relation '{}' not found in grammar config", relation);
            }
        } else {
            logger.debug("grammarConfig is null");
        }

        if (bcqlQuery == null || bcqlQuery.isEmpty()) {
            bcqlQuery = String.format("\"%s\" []{0,5} \"%s\"",
                word1.toLowerCase(), word2.toLowerCase());
            logger.debug("Using fallback BCQL: {}", bcqlQuery);
        }

        List<QueryResults.ConcordanceResult> results = executor.executeBcqlQuery(bcqlQuery, limit);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("word1", word1);
        response.put("word2", word2);
        response.put("relation", relation);
        response.put("bcql", bcqlQuery);
        response.put("limit_requested", limit);
        response.put("total", results.size());

        List<Map<String, Object>> examplesList = new ArrayList<>();
        for (QueryResults.ConcordanceResult r : results) {
            Map<String, Object> exMap = new HashMap<>();
            exMap.put("sentence", r.getSentence());
            exMap.put("highlighted", r.getSentence());
            exMap.put("raw", r.getRawXml() != null ? r.getRawXml() : r.getSentence());
            examplesList.add(exMap);
        }
        response.put("examples", examplesList);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Render radial plot.
     * POST /api/visual/radial
     * Body JSON: { center: "word", width: 840, height: 520, items: [{label:"", score: 3.2}, ...] }
     * Returns: image/svg+xml
     */
    void handleVisualRadial(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            HttpApiUtils.sendOptionsResponse(exchange, "POST");
            return;
        }
        if (!HttpApiUtils.requireMethod(exchange, "POST")) {
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            logger.debug("Radial: body = {}", body);
            JSONObject obj = JSON.parseObject(body);
            String center = obj.getString("center");
            if (center == null) center = "";
            logger.debug("Radial: center = {}", center);
            int width = obj.getIntValue("width") == 0 ? 840 : obj.getIntValue("width");
            int height = obj.getIntValue("height") == 0 ? 520 : obj.getIntValue("height");

            JSONArray itemsArr = obj.getJSONArray("items");
            List<RadialPlot.Item> items = new ArrayList<>();
            if (itemsArr != null) {
                int limit = Math.min(40, itemsArr.size());
                for (int i = 0; i < limit; i++) {
                    JSONObject it = itemsArr.getJSONObject(i);
                    String label = it.getString("label");
                    double score = it.getDoubleValue("score");
                    items.add(new RadialPlot.Item(label, score));
                }
            }
            String mode = obj.getString("mode");

            String svg = RadialPlot.renderFromItems(center, items, width, height, mode);
            byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
            HttpApiUtils.sendBinaryResponse(exchange, "image/svg+xml; charset=utf-8", bytes);
        } catch (Exception e) {
            logger.error("Error rendering radial", e);
            HttpApiUtils.sendError(exchange, 500, "Failed to render radial: " + e.getMessage());
        }
    }

    /**
     * Handle arbitrary BCQL query (POST with JSON body to avoid URL encoding issues).
     * POST /api/bcql with body: {"query": "[lemma=\"test\"]", "limit": 20}
     */
    void handleBcqlQueryPost(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            HttpApiUtils.sendOptionsResponse(exchange, "POST");
            return;
        }
        if (!HttpApiUtils.requireMethod(exchange, "POST")) {
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JSONObject obj = JSON.parseObject(body);
            String bcqlQuery = obj.getString("query");
            if (bcqlQuery == null || bcqlQuery.isBlank()) {
                HttpApiUtils.sendError(exchange, 400, "Missing required parameter: query");
                return;
            }
            int limit = obj.getIntValue("limit");
            if (limit <= 0) limit = 20;

            logger.debug("BCQL query: {}", bcqlQuery);

            List<QueryResults.ConcordanceResult> results = executor.executeBcqlQuery(bcqlQuery, limit);

            Map<String, Object> response = new HashMap<>();
            response.put("query", bcqlQuery);
            response.put("total", results.size());
            response.put("limit", limit);

            List<Map<String, Object>> resultsList = new ArrayList<>();
            for (QueryResults.ConcordanceResult r : results) {
                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("sentence", r.getSentence());
                if (r.getRawXml() != null) {
                    resultMap.put("raw", r.getRawXml());
                }
                resultMap.put("match_start", r.getStartOffset());
                resultMap.put("match_end", r.getEndOffset());
                if (r.getCollocateLemma() != null) {
                    resultMap.put("collocate_lemma", r.getCollocateLemma());
                    resultMap.put("frequency", r.getFrequency());
                    resultMap.put("log_dice", r.getLogDice());
                }
                resultsList.add(resultMap);
            }
            response.put("results", resultsList);

            HttpApiUtils.sendJsonResponse(exchange, response);
        } catch (IOException e) {
            logger.error("BCQL query I/O error", e);
            HttpApiUtils.sendError(exchange, 500, "BCQL query failed: " + e.getMessage());
        } catch (Exception e) {
            logger.error("BCQL query error", e);
            HttpApiUtils.sendError(exchange, 400, "BCQL query error: " + e.getMessage());
        }
    }
}

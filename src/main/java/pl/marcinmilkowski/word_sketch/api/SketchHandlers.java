package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSONArray;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.model.RelationType;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.model.QueryResults;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * HTTP handlers for word sketch endpoints.
 */
class SketchHandlers {

    private static final Logger logger = LoggerFactory.getLogger(SketchHandlers.class);

    private final QueryExecutor executor;
    private final GrammarConfig grammarConfig;

    SketchHandlers(QueryExecutor executor, GrammarConfig grammarConfig) {
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
            if (specificDeprel != null && !specificDeprel.isEmpty()) {
                handleDependencyRelationQuery(exchange, lemma, specificDeprel);
            } else {
                handleFullDependencySketch(exchange, lemma);
            }
            return;
        }

        String relation = parts.length > 1 ? parts[1] : null;
        if (relation != null && !relation.isEmpty()) {
            handleRelationSketch(exchange, lemma, relation);
        } else {
            handleFullSketch(exchange, lemma);
        }
    }

    void handleSurfaceRelations(HttpExchange exchange) throws IOException {
        handleRelationsForType(exchange, RelationType.SURFACE);
    }

    void handleDepRelations(HttpExchange exchange) throws IOException {
        handleRelationsForType(exchange, RelationType.DEP);
    }

    private void handleRelationsForType(HttpExchange exchange, RelationType relationType) throws IOException {
        JSONArray relationsArray = new JSONArray();
        for (var rel : grammarConfig.getRelations()) {
            if (rel.relationType().orElse(null) == relationType) {
                Map<String, Object> obj = new HashMap<>();
                obj.put("id", rel.id());
                obj.put("name", rel.name());
                obj.put("description", rel.description());
                obj.put("relation_type", rel.relationType().orElseThrow(
                        () -> new java.util.NoSuchElementException("relationType absent for relation: " + rel.id())).name());
                obj.put("pattern", rel.pattern());
                if (relationType == RelationType.DEP) {
                    obj.put("deprel", rel.getDeprel());
                }
                relationsArray.add(obj);
            }
        }
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("relations", relationsArray);
        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    private void handleFullSketch(HttpExchange exchange, String lemma) throws IOException {
        handleFullSketchForType(exchange, lemma, RelationType.SURFACE);
    }

    private void handleRelationSketch(HttpExchange exchange, String lemma, String relation) throws IOException {
        handleRelationQueryForPattern(exchange, lemma, relation, RelationType.SURFACE);
    }

    /**
     * Handle full dependency sketch request.
     * Returns all dependency relations for the given lemma.
     * DEP relations use surface patterns with [deprel="..."] constraints.
     */
    private void handleFullDependencySketch(HttpExchange exchange, String lemma) throws IOException {
        handleDepSketchForType(exchange, lemma);
    }

    private void handleFullSketchForType(HttpExchange exchange, String lemma, RelationType relationType) throws IOException {
        Map<String, Object> byRelation = new HashMap<>();
        List<String> relationErrors = new ArrayList<>();

        executeRelationQueries(relationType, rel -> true, lemma, byRelation, relationErrors, (rel, sketch) -> {
            Map<String, Object> relData = new HashMap<>();
            relData.put("name", rel.name());
            relData.put("cql", rel.pattern());
            relData.put("collocate_pos_group", rel.collocatePosGroup().getValue());
            relData.put("collocations", sketch.collocations());
            return relData;
        });

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("lemma", lemma);
        response.put("relations", byRelation);
        if (!relationErrors.isEmpty()) {
            response.put("errors", relationErrors);
        }
        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Handle full dependency sketch — separate dispatch path from surface sketches.
     * DEP relations always use {@link #buildRelationResponse} and carry a
     * {@code "type": "dependency"} field in the response.
     */
    private void handleDepSketchForType(HttpExchange exchange, String lemma) throws IOException {
        Map<String, Object> byRelation = new HashMap<>();
        List<String> relationErrors = new ArrayList<>();

        executeRelationQueries(RelationType.DEP, rel -> rel.getDeprel() != null, lemma, byRelation, relationErrors,
            (rel, sketch) -> buildRelationResponse(rel, sketch.results(), sketch.collocations()));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("lemma", lemma);
        response.put("type", "dependency");
        response.put("relations", byRelation);
        if (!relationErrors.isEmpty()) {
            response.put("errors", relationErrors);
        }
        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Shared loop that iterates over grammar relations of the given type, executes each one,
     * and delegates response-map construction to the provided {@code builder} callback.
     * Relations that execute with no results are skipped; errors are collected in
     * {@code relationErrors} rather than propagated.
     *
     * @param relationType    the type of relations to process
     * @param extraFilter     additional per-relation predicate (e.g. {@code rel -> rel.getDeprel() != null})
     * @param lemma           the head lemma being sketched
     * @param byRelation      accumulator map from relation-id to response map
     * @param relationErrors  accumulator list for error strings
     * @param builder         callback that converts a relation config + executed sketch into the JSON-ready map
     */
    private void executeRelationQueries(
            RelationType relationType,
            Predicate<pl.marcinmilkowski.word_sketch.config.RelationConfig> extraFilter,
            String lemma,
            Map<String, Object> byRelation,
            List<String> relationErrors,
            BiFunction<pl.marcinmilkowski.word_sketch.config.RelationConfig, ExecutedSketch, Map<String, Object>> builder) {
        for (var rel : grammarConfig.getRelations()) {
            if (rel.relationType().orElse(null) != relationType) continue;
            if (!extraFilter.test(rel)) continue;
            try {
                ExecutedSketch sketch = executeAndFormatCollocations(lemma, rel);
                if (sketch != null) {
                    byRelation.put(rel.id(), builder.apply(rel, sketch));
                }
            } catch (IOException | RuntimeException e) {
                logger.warn("Relation {} failed for lemma {}: {}", rel.id(), lemma, e.getMessage());
                relationErrors.add(rel.id() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Handle specific dependency relation query.
     * Returns collocates for a single dependency relation.
     * DEP relations use surface patterns with [deprel="..."] constraints.
     */
    private void handleDependencyRelationQuery(HttpExchange exchange, String lemma, String relationId) throws IOException {
        handleRelationQueryForPattern(exchange, lemma, relationId, RelationType.DEP);
    }

    private void handleRelationQueryForPattern(HttpExchange exchange, String lemma, String relationId, RelationType relationType) throws IOException {
        var rel = grammarConfig.getRelation(relationId).orElse(null);
        if (rel == null || rel.relationType().orElse(null) != relationType) {
            HttpApiUtils.sendError(exchange, 400, "Unknown relation: " + relationId);
            return;
        }

        String fullPattern = rel.buildFullPattern(lemma);
        List<QueryResults.WordSketchResult> results = executor.executeSurfacePattern(
            lemma, fullPattern,
            0.0, 50);

        List<Map<String, Object>> collocations = new ArrayList<>();
        for (QueryResults.WordSketchResult result : results) {
            collocations.add(formatWordSketchResult(result));
        }

        Map<String, Object> relData = new HashMap<>();
        relData.put("id", rel.id());
        relData.put("name", rel.name());
        relData.put("collocations", collocations);
        relData.put("total_matches", results.stream().mapToLong(QueryResults.WordSketchResult::frequency).sum());

        Map<String, Object> relationsMap = new HashMap<>();
        relationsMap.put(rel.id(), relData);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("lemma", lemma);
        response.put("relations", relationsMap);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Executes the surface pattern for a relation and formats the results into the JSON-ready collocations list.
     * Returns {@code null} when the query returns no results (so callers can skip the relation).
     */
    private ExecutedSketch executeAndFormatCollocations(String lemma,
            pl.marcinmilkowski.word_sketch.config.RelationConfig rel) throws IOException {
        String fullPattern = rel.buildFullPattern(lemma);
        List<QueryResults.WordSketchResult> results = executor.executeSurfacePattern(lemma, fullPattern, 0.0, 20);
        if (results.isEmpty()) return null;
        List<Map<String, Object>> collocations = new ArrayList<>();
        for (QueryResults.WordSketchResult result : results) {
            collocations.add(formatWordSketchResult(result));
        }
        return new ExecutedSketch(results, collocations);
    }

    private record ExecutedSketch(
            List<QueryResults.WordSketchResult> results,
            List<Map<String, Object>> collocations) {}

    /**
     * Builds the response map for a single dependency relation entry.
     * Extracted from the {@code isDep} branch of {@code handleFullSketchForType}.
     */
    private static Map<String, Object> buildRelationResponse(
            pl.marcinmilkowski.word_sketch.config.RelationConfig rel,
            List<QueryResults.WordSketchResult> results,
            List<Map<String, Object>> collocations) {
        Map<String, Object> relData = new HashMap<>();
        relData.put("id", rel.id());
        relData.put("name", rel.name());
        relData.put("description", rel.description());
        relData.put("deprel", rel.getDeprel());
        relData.put("total_matches", results.stream().mapToLong(QueryResults.WordSketchResult::frequency).sum());
        relData.put("collocations", collocations);
        return relData;
    }

    private static Map<String, Object> formatWordSketchResult(QueryResults.WordSketchResult result) {
        Map<String, Object> word = new HashMap<>();
        word.put("lemma", result.lemma());
        word.put("frequency", result.frequency());
        word.put("log_dice", result.logDice());
        word.put("pos", result.pos());
        return word;
    }
}

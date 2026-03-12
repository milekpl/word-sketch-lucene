package pl.marcinmilkowski.word_sketch.api;

import com.sun.net.httpserver.HttpExchange;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.RelationPatternUtils;
import pl.marcinmilkowski.word_sketch.config.RelationType;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.model.QueryResults;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * HTTP handlers for word sketch endpoints.
 */
class SketchHandlers {

    private static final Logger logger = LoggerFactory.getLogger(SketchHandlers.class);

    /** Default result limit for full-sketch queries where results are aggregated across many relations. */
    private static final int DEFAULT_SKETCH_RESULTS = 20;
    /** Higher limit for single-relation queries where the caller has already narrowed to one relation. */
    private static final int SINGLE_RELATION_RESULTS = 50;

    private final QueryExecutor executor;
    private final GrammarConfig grammarConfig;

    SketchHandlers(QueryExecutor executor, @NonNull GrammarConfig grammarConfig) {
        this.executor = executor;
        this.grammarConfig = grammarConfig;
    }

    void routeSketchRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.substring("/api/sketch/".length()).split("/");

        if (parts.length == 0 || parts[0].isEmpty()) {
            throw new IllegalArgumentException("Lemma required");
        }

        String lemma = parts[0];
        if (lemma.length() > HttpApiUtils.MAX_PARAM_LENGTH) {
            throw new IllegalArgumentException("Lemma exceeds maximum length of " + HttpApiUtils.MAX_PARAM_LENGTH + " characters");
        }

        if (parts.length > 1 && "dep".equals(parts[1])) {
            String specificDeprel = parts.length > 2 ? parts[2] : null;
            if (specificDeprel != null) {
                handleDependencyRelationQuery(exchange, lemma, specificDeprel);
            } else {
                // Full dependency sketch — all DEP-type relations
                handleDependencySketch(exchange, lemma);
            }
            return;
        }

        String relation = parts.length > 1 ? parts[1] : null;
        if (relation != null && !relation.isEmpty()) {
            handleRelationQueryForPattern(exchange, lemma, relation, RelationType.SURFACE);
        } else {
            handleFullSketchForType(exchange, lemma, RelationType.SURFACE);
        }
    }

    void handleRelationsForType(HttpExchange exchange, RelationType relationType) throws IOException {
        List<Map<String, Object>> relationsArray = new ArrayList<>();
        for (var rel : grammarConfig.relations()) {
            if (rel.relationType().filter(rt -> rt == relationType).isPresent()) {
                Map<String, Object> obj = new HashMap<>();
                obj.put("id", rel.id());
                obj.put("name", rel.name());
                obj.put("description", rel.description());
                obj.put("relation_type", relationType.name());
                obj.put("pattern", rel.pattern());
                if (relationType == RelationType.DEP) {
                    obj.put("deprel", rel.deriveDeprel());
                }
                relationsArray.add(obj);
            }
        }
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("relations", relationsArray);
        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    private void handleFullSketchForType(HttpExchange exchange, String lemma, RelationType relationType) throws IOException {
        Map<String, Object> byRelation = new HashMap<>();
        List<String> relationErrors = new ArrayList<>();

        executeRelationQueries(relationType, rel -> true, lemma, byRelation, relationErrors, (rel, sketch) ->
            SketchResponseAssembler.buildSurfaceRelationEntry(rel, sketch.collocations(),
                sketch.results().stream().mapToLong(QueryResults.WordSketchResult::frequency).sum()));

        Map<String, Object> response = new HashMap<>();
        response.put("lemma", lemma);
        response.put("relations", byRelation);
        if (!relationErrors.isEmpty()) {
            response.put("status", "partial");
            response.put("warnings", relationErrors);
        } else {
            response.put("status", "ok");
        }
        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Full dependency sketch — dispatches DEP-type relations, each carrying a
     * {@code "type": "dependency"} field in the response.
     */
    private void handleDependencySketch(HttpExchange exchange, String lemma) throws IOException {
        Map<String, Object> byRelation = new HashMap<>();
        List<String> relationErrors = new ArrayList<>();

        executeRelationQueries(RelationType.DEP, rel -> rel.deriveDeprel() != null, lemma, byRelation, relationErrors,
            (rel, sketch) -> SketchResponseAssembler.buildDepRelationEntry(rel, sketch.results(), sketch.collocations()));

        Map<String, Object> response = new HashMap<>();
        response.put("lemma", lemma);
        response.put("type", "dependency");
        response.put("relations", byRelation);
        if (!relationErrors.isEmpty()) {
            response.put("status", "partial");
            response.put("warnings", relationErrors);
        } else {
            response.put("status", "ok");
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
     * @param extraFilter     additional per-relation predicate (e.g. {@code rel -> rel.deriveDeprel() != null})
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
        for (var rel : grammarConfig.relations()) {
            if (rel.relationType().orElse(null) != relationType) continue;
            if (!extraFilter.test(rel)) continue;
            try {
                Optional<ExecutedSketch> sketchOpt = buildSketch(lemma, rel);
                sketchOpt.ifPresent(sketch -> byRelation.put(rel.id(), builder.apply(rel, sketch)));
            } catch (IOException e) {
                logger.warn("Relation {} failed for lemma {}: {}", rel.id(), lemma, e.getMessage());
                relationErrors.add(e.getMessage());
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
        var rel = grammarConfig.relation(relationId).orElse(null);
        if (rel == null) {
            throw new IllegalArgumentException("Unknown relation: " + relationId);
        }
        if (rel.relationType().orElse(null) != relationType) {
            String actualType = rel.relationType().map(RelationType::name).orElse("(none)");
            throw new IllegalArgumentException(
                "Relation '" + relationId + "' has type " + actualType + "; expected " + relationType.name());
        }

        List<QueryResults.WordSketchResult> results = executor.executeSurfacePattern(
            RelationPatternUtils.buildFullPattern(rel, lemma), 0.0, SINGLE_RELATION_RESULTS);

        List<Map<String, Object>> collocations = new ArrayList<>();
        for (QueryResults.WordSketchResult result : results) {
            collocations.add(SketchResponseAssembler.formatWordSketchResult(result));
        }

        long totalMatches = results.stream().mapToLong(QueryResults.WordSketchResult::frequency).sum();
        Map<String, Object> relData = SketchResponseAssembler.buildSurfaceRelationEntry(rel, collocations, totalMatches);

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
     * Returns {@link Optional#empty()} when the query returns no results (so callers can skip the relation).
     */
    private Optional<ExecutedSketch> buildSketch(String lemma,
            pl.marcinmilkowski.word_sketch.config.RelationConfig rel) throws IOException {
        List<QueryResults.WordSketchResult> results = executor.executeSurfacePattern(
            RelationPatternUtils.buildFullPattern(rel, lemma), 0.0, DEFAULT_SKETCH_RESULTS);
        if (results.isEmpty()) return Optional.empty();
        List<Map<String, Object>> collocations = new ArrayList<>();
        for (QueryResults.WordSketchResult result : results) {
            collocations.add(SketchResponseAssembler.formatWordSketchResult(result));
        }
        return Optional.of(new ExecutedSketch(results, collocations));
    }

    private record ExecutedSketch(
            List<QueryResults.WordSketchResult> results,
            List<Map<String, Object>> collocations) {}
}

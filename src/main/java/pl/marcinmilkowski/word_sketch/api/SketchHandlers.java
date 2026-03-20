package pl.marcinmilkowski.word_sketch.api;

import com.sun.net.httpserver.HttpExchange;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.api.model.CollocateEntry;
import pl.marcinmilkowski.word_sketch.api.model.RelationEntry;
import pl.marcinmilkowski.word_sketch.api.model.RelationListEntry;
import pl.marcinmilkowski.word_sketch.api.model.RelationListResponse;
import pl.marcinmilkowski.word_sketch.api.model.SketchResponse;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.RelationUtils;
import pl.marcinmilkowski.word_sketch.model.PosGroup;
import pl.marcinmilkowski.word_sketch.model.RelationType;
import pl.marcinmilkowski.word_sketch.query.spi.SketchQueryPort;
import pl.marcinmilkowski.word_sketch.model.sketch.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP handlers for word sketch endpoints.
 */
class SketchHandlers {

    private static final Logger logger = LoggerFactory.getLogger(SketchHandlers.class);

    /** Default result limit for full-sketch queries where results are aggregated across many relations. */
    private static final int DEFAULT_SKETCH_RESULTS = 20;
    /** Higher limit for single-relation queries where the caller has already narrowed to one relation. */
    private static final int SINGLE_RELATION_RESULTS = 50;
    /**
     * Bounded worker pool for full-sketch relation batches. A small shared pool is enough to overlap
     * independent BlackLab relation queries without flooding the index with dozens of parallel searches.
     */
    private static final int RELATION_QUERY_THREADS =
            Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
    private static final AtomicInteger RELATION_QUERY_THREAD_IDS = new AtomicInteger();

    private final SketchQueryPort executor;
    private final GrammarConfig grammarConfig;
    private final ExecutorService relationQueryExecutor;

    SketchHandlers(SketchQueryPort executor, @NonNull GrammarConfig grammarConfig) {
        this.executor = executor;
        this.grammarConfig = grammarConfig;
        this.relationQueryExecutor = createRelationQueryExecutor();
    }

    void routeSketchRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Map<String, String> params = HttpApiUtils.parseQueryParams(exchange.getRequestURI().getQuery());
        String[] parts = path.substring("/api/sketch/".length()).split("/");

        if (parts.length == 0 || parts[0].isEmpty()) {
            throw new IllegalArgumentException("Lemma required");
        }

        String lemma = parts[0];
        if (lemma.length() > HttpApiUtils.MAX_PARAM_LENGTH) {
            throw new IllegalArgumentException("Lemma exceeds maximum length of " + HttpApiUtils.MAX_PARAM_LENGTH + " characters");
        }

        String format = ExportUtils.parseFormat(params);
        int exportLimit = ExportUtils.parseExportLimit(params);

        if (parts.length > 1 && RelationType.DEP.label().equals(parts[1])) {
            // NOTE: "dep" is a reserved URL path segment that acts as a sketch-type
            // discriminator. It must never be assigned as a grammar relation ID to avoid
            // routing collisions — /api/sketch/{lemma}/dep always means "full dep sketch",
            // not a surface relation named "dep".
            String specificDeprel = parts.length > 2 ? parts[2] : null;
            if (specificDeprel != null) {
                handleRelationQueryById(exchange, lemma, specificDeprel, RelationType.DEP, format, exportLimit);
            } else {
                // Full dependency sketch — all DEP-type relations
                handleDependencySketch(exchange, lemma, format, exportLimit);
            }
            return;
        }

        String relation = parts.length > 1 ? parts[1] : null;
        if (relation != null && !relation.isEmpty()) {
            handleRelationQueryById(exchange, lemma, relation, RelationType.SURFACE, format, exportLimit);
        } else {
            handleFullSketchForType(
                    exchange,
                    lemma,
                    RelationType.SURFACE,
                    surfaceRelationFilter(parseSurfacePosFilter(params), parseHeadPosFilter(params)),
                    format,
                    exportLimit);
        }
    }

    void handleRelationsForType(HttpExchange exchange, RelationType relationType) throws IOException {
        List<RelationListEntry> relationsArray = new ArrayList<>();
        for (var rel : grammarConfig.relations()) {
            if (rel.relationType().filter(rt -> rt == relationType).isPresent()) {
                relationsArray.add(new RelationListEntry(
                        rel.id(),
                        rel.name(),
                        rel.description(),
                        relationType.name(),
                    RelationUtils.computeHeadPosGroup(rel).label(),
                    rel.collocatePosGroup().label(),
                        rel.pattern(),
                        relationType == RelationType.DEP ? rel.deriveDeprel() : null));
            }
        }
        HttpApiUtils.sendJsonResponse(exchange, new RelationListResponse("ok", relationsArray));
    }

    private void handleFullSketchForType(
            HttpExchange exchange,
            String lemma,
            RelationType relationType,
            Predicate<pl.marcinmilkowski.word_sketch.config.RelationConfig> extraFilter,
            String format,
            int exportLimit) throws IOException {
        RelationQueryBatch batch = executeRelationQueries(relationType, extraFilter, lemma,
            (rel, sketch) -> SketchResponseAssembler.buildSurfaceRelationEntry(rel, sketch.collocations(),
                sketch.results().stream().mapToLong(WordSketchResult::frequency).sum()));

        SketchResponse response = buildSketchResponse(lemma, null, batch.results(), batch.errors());
        sendSketchResponse(exchange, response, format, exportLimit, lemma + "-sketch");
    }

    private static Predicate<pl.marcinmilkowski.word_sketch.config.RelationConfig> surfaceRelationFilter(
            Optional<PosGroup> collocatePosFilter,
            Optional<PosGroup> headPosFilter) {
        return rel -> collocatePosFilter.map(pos -> rel.collocatePosGroup() == pos).orElse(true)
                && headPosFilter.map(pos -> RelationUtils.computeHeadPosGroup(rel) == pos).orElse(true);
    }

    private static Optional<PosGroup> parseSurfacePosFilter(Map<String, String> params) {
        return parsePosFilter(params, "pos");
    }

    private static Optional<PosGroup> parseHeadPosFilter(Map<String, String> params) {
        return parsePosFilter(params, "head_pos");
    }

    private static Optional<PosGroup> parsePosFilter(Map<String, String> params, String paramName) {
        String raw = params.get(paramName);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        if (raw.length() > HttpApiUtils.MAX_PARAM_LENGTH) {
            throw new IllegalArgumentException(
                    "Parameter '" + paramName + "' exceeds maximum length of " + HttpApiUtils.MAX_PARAM_LENGTH + " characters");
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "noun" -> Optional.of(PosGroup.NOUN);
            case "verb" -> Optional.of(PosGroup.VERB);
            case "adj" -> Optional.of(PosGroup.ADJ);
            case "adv" -> Optional.of(PosGroup.ADV);
            default -> throw new IllegalArgumentException(
                    "Invalid " + paramName + " parameter: " + raw + " (expected noun, verb, adj, adv)");
        };
    }

    /**
     * Full dependency sketch — dispatches DEP-type relations, each carrying a
     * {@code "type": "dependency"} field in the response.
     */
    private void handleDependencySketch(HttpExchange exchange, String lemma, String format, int exportLimit) throws IOException {
        RelationQueryBatch batch = executeRelationQueries(RelationType.DEP, rel -> rel.deriveDeprel() != null, lemma,
            (rel, sketch) -> SketchResponseAssembler.buildDepRelationEntry(rel, sketch.results(), sketch.collocations()));

        SketchResponse response = buildSketchResponse(lemma, "dependency", batch.results(), batch.errors());
        sendSketchResponse(exchange, response, format, exportLimit, lemma + "-dep-sketch");
    }

    /** Builds a typed {@link SketchResponse}; {@code type} is {@code null} for surface-sketch responses. */
    private static SketchResponse buildSketchResponse(
            String lemma, String type,
            Map<String, RelationEntry> byRelation,
            List<String> relationErrors) {
        String status = relationErrors.isEmpty() ? "ok" : "partial";
        List<String> warnings = relationErrors.isEmpty() ? null : relationErrors;
        return new SketchResponse(status, lemma, type, byRelation, warnings);
    }

    /**
     * Shared loop that iterates over grammar relations of the given type, executes each one,
     * and delegates response-record construction to the provided {@code builder} callback.
     * Relations that execute with no results are skipped; errors are collected in the returned
     * {@link RelationQueryBatch} rather than propagated.
     *
     * <p><b>Intentional asymmetry with exploration handlers:</b> A word sketch is a composite
     * multi-relation view. Propagating on the first failure would discard all successfully
     * computed relations. Instead, per-relation failures are collected and returned as a
     * {@code "partial"} response with a {@code warnings} array so callers receive the
     * successfully computed data alongside the failure details.</p>
     *
     * @param relationType the type of relations to process
     * @param extraFilter  additional per-relation predicate (e.g. {@code rel -> rel.deriveDeprel() != null})
     * @param lemma        the head lemma being sketched
     * @param builder      converts a relation config + executed sketch into a {@link RelationEntry}
     * @return a batch holding the populated results map and any per-relation error messages
     */
    private RelationQueryBatch executeRelationQueries(
            RelationType relationType,
            Predicate<pl.marcinmilkowski.word_sketch.config.RelationConfig> extraFilter,
            String lemma,
            RelationEntryBuilder builder) {
        List<pl.marcinmilkowski.word_sketch.config.RelationConfig> selectedRelations = grammarConfig.relations().stream()
                .filter(rel -> rel.relationType().orElse(null) == relationType)
                .filter(extraFilter)
                .toList();
        Map<String, RelationEntry> byRelation = new LinkedHashMap<>();
        List<String> relationErrors = new ArrayList<>();

        List<CompletableFuture<RelationTaskResult>> futures = selectedRelations.stream()
                .map(rel -> CompletableFuture.supplyAsync(
                        () -> executeRelationQueryTask(lemma, rel), relationQueryExecutor))
                .toList();

        for (int i = 0; i < selectedRelations.size(); i++) {
            var rel = selectedRelations.get(i);
            RelationTaskResult taskResult = awaitRelationTask(rel, lemma, futures.get(i));
            if (taskResult.error() != null) {
                relationErrors.add(taskResult.error());
                continue;
            }
            try {
                taskResult.sketch().ifPresent(sketch -> byRelation.put(rel.id(), builder.build(rel, sketch)));
            } catch (RuntimeException e) {
                logger.warn("Relation {} failed for lemma {} during response assembly: {}", rel.id(), lemma,
                        e.getMessage(), e);
                relationErrors.add(formatRelationError(rel, e));
            }
        }
        return new RelationQueryBatch(byRelation, relationErrors);
    }

    private RelationTaskResult executeRelationQueryTask(
            String lemma,
            pl.marcinmilkowski.word_sketch.config.RelationConfig rel) {
        try {
            return new RelationTaskResult(buildSketch(lemma, rel), null);
        } catch (IOException | RuntimeException e) {
            logger.warn("Relation {} failed for lemma {}: {}", rel.id(), lemma, e.getMessage(), e);
            return new RelationTaskResult(Optional.empty(), formatRelationError(rel, e));
        }
    }

    private static RelationTaskResult awaitRelationTask(
            pl.marcinmilkowski.word_sketch.config.RelationConfig rel,
            String lemma,
            CompletableFuture<RelationTaskResult> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.warn("Relation {} failed for lemma {}: {}", rel.id(), lemma, cause.getMessage(), cause);
            return new RelationTaskResult(Optional.empty(), formatRelationError(rel, cause));
        }
    }

    private static String formatRelationError(
            pl.marcinmilkowski.word_sketch.config.RelationConfig rel,
            Throwable error) {
        String message = error.getMessage();
        return rel.id() + ": " + ((message == null || message.isBlank())
                ? error.getClass().getSimpleName()
                : message);
    }

    private static ExecutorService createRelationQueryExecutor() {
        return Executors.newFixedThreadPool(RELATION_QUERY_THREADS, runnable -> {
            Thread thread = new Thread(runnable,
                    "sketch-relations-" + RELATION_QUERY_THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    void close() {
        relationQueryExecutor.shutdown();
    }

    /** Holds the per-relation results and any error messages from a batch of relation queries. */
    private record RelationQueryBatch(Map<String, RelationEntry> results, List<String> errors) {}
    private record RelationTaskResult(Optional<ExecutedSketch> sketch, String error) {}

    /** Builds a {@link RelationEntry} from a relation config and its executed sketch. */
    @FunctionalInterface
    private interface RelationEntryBuilder {
        RelationEntry build(pl.marcinmilkowski.word_sketch.config.RelationConfig rel, ExecutedSketch sketch);
    }

    private void handleRelationQueryById(HttpExchange exchange, String lemma, String relationId, RelationType relationType, String format, int exportLimit) throws IOException {
        var rel = grammarConfig.relation(relationId).orElse(null);
        if (rel == null) {
            throw new IllegalArgumentException("Unknown relation: " + relationId);
        }
        if (rel.relationType().orElse(null) != relationType) {
            String actualType = rel.relationType().map(RelationType::name).orElse("(none)");
            throw new IllegalArgumentException(
                "Relation '" + relationId + "' has type " + actualType + "; expected " + relationType.name());
        }

        List<WordSketchResult> results = executor.executeSurfaceCollocations(
            RelationUtils.buildFullPattern(rel, lemma), 0.0, SINGLE_RELATION_RESULTS);

        List<CollocateEntry> collocations = results.stream()
                .map(SketchResponseAssembler::toCollocateEntry)
                .toList();

        long totalMatches = results.stream().mapToLong(WordSketchResult::frequency).sum();
        RelationEntry relEntry = SketchResponseAssembler.buildSurfaceRelationEntry(rel, collocations, totalMatches);
        Map<String, RelationEntry> relationsMap = Map.of(rel.id(), relEntry);

        SketchResponse response = new SketchResponse("ok", lemma, null, relationsMap, null);
        sendSketchResponse(exchange, response, format, exportLimit, lemma + "-" + relationId);
    }

    /**
     * Executes the surface pattern for a relation and formats the results into typed collocate entries.
     * Returns {@link Optional#empty()} when the query returns no results (so callers can skip the relation).
     */
    private Optional<ExecutedSketch> buildSketch(String lemma,
            pl.marcinmilkowski.word_sketch.config.RelationConfig rel) throws IOException {
        List<WordSketchResult> results = executor.executeSurfaceCollocations(
            RelationUtils.buildFullPattern(rel, lemma), 0.0, DEFAULT_SKETCH_RESULTS);
        if (results.isEmpty()) return Optional.empty();
        List<CollocateEntry> collocations = results.stream()
                .map(SketchResponseAssembler::toCollocateEntry)
                .toList();
        return Optional.of(new ExecutedSketch(results, collocations));
    }

    private static void sendSketchResponse(
            HttpExchange exchange, SketchResponse response,
            String format, int exportLimit, String context) throws IOException {
        switch (format) {
            case "csv" -> HttpApiUtils.sendCsvResponse(exchange,
                    ExportUtils.sketchToCsv(response, exportLimit),
                    ExportUtils.downloadFilename(context, "csv"));
            case "xml" -> HttpApiUtils.sendXmlResponse(exchange,
                    ExportUtils.sketchToXml(response, exportLimit),
                    ExportUtils.downloadFilename(context, "xml"));
            default -> HttpApiUtils.sendJsonResponse(exchange, response);
        }
    }

    private record ExecutedSketch(
            List<WordSketchResult> results,
            List<CollocateEntry> collocations) {}
}

package pl.marcinmilkowski.word_sketch.api;

import com.sun.net.httpserver.HttpExchange;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.config.RelationUtils;
import pl.marcinmilkowski.word_sketch.model.exploration.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.exploration.FetchExamplesOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.FetchExamplesResult;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.SingleSeedExplorationOptions;
import pl.marcinmilkowski.word_sketch.exploration.ExplorationException;
import pl.marcinmilkowski.word_sketch.exploration.spi.ExplorationService;

import pl.marcinmilkowski.word_sketch.api.model.ComparisonResponse;
import pl.marcinmilkowski.word_sketch.api.model.ExamplesResponse;
import pl.marcinmilkowski.word_sketch.api.model.ExploreResponse;

import java.util.Objects;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP handlers for semantic field exploration endpoints.
 */
class ExplorationHandlers {

    private static final Logger logger = LoggerFactory.getLogger(ExplorationHandlers.class);

    private final GrammarConfig grammarConfig;
    private final ExplorationService explorationService;

    /** @see RelationUtils#CROSS_RELATIONAL_SENTINEL */
    private static final String CROSS_RELATIONAL = RelationUtils.CROSS_RELATIONAL_SENTINEL;

    ExplorationHandlers(ExplorationService explorationService, @NonNull GrammarConfig grammarConfig) {
        this.grammarConfig = Objects.requireNonNull(grammarConfig,
            "grammarConfig must not be null; exploration endpoints require a loaded grammar configuration");
        this.explorationService = Objects.requireNonNull(explorationService,
            "explorationService must not be null");
    }

    /**
     * Accepts a single {@code seed} noun and returns its semantic neighbourhood under the
     * requested grammatical relation.
     *
     * <p>GET /api/semantic-field/explore?seed=house&amp;relation=adj_predicate&amp;top=15&amp;min_shared=2&amp;min_logdice=3.0</p>
     *
     * <p>Cardinality: accepts exactly one {@code seed} value. Providing comma-separated values
     * is not supported — use {@code /api/semantic-field/explore-multi} for multiple seeds.</p>
     *
     * @param exchange the HTTP exchange; receives a 400 if {@code seed} is missing or the
     *                 relation is unknown
     */
    void handleSemanticFieldExplore(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> params = HttpApiUtils.parseQueryParams(exchange.getRequestURI().getQuery());
            String seed = HttpApiUtils.requireParam(params, "seed");
            RelationConfig resolvedConfig = resolveRelationConfig(params);
            ExplorationOptions opts = parseExplorationOptions(params);
            int nounsPerSeed = HttpApiUtils.parseIntParam(params, "nouns_per", 30);
            String format = ExportUtils.parseFormat(params);
            int exportLimit = ExportUtils.parseExportLimit(params);

            SingleSeedExplorationOptions singleSeedOpts = new SingleSeedExplorationOptions(opts, nounsPerSeed);

            ExplorationResult result = explorationService.exploreByRelation(seed, resolvedConfig, singleSeedOpts);

            ExploreResponse response = ExploreResponseAssembler.buildSingleSeedExploreResponse(
                    result, resolvedConfig.id(), opts, nounsPerSeed);

            sendExploreResponse(exchange, response, format, exportLimit, seed + "-explore");
        } catch (ExplorationException e) {
            sendExplorationError(exchange, "Semantic field explore", e);
        }
    }

    /**
     * Accepts two or more comma-separated {@code seeds} and returns the collocates shared
     * across all seeds under the requested grammatical relation.
     *
     * <p>GET /api/semantic-field/explore-multi?seeds=theory,model,hypothesis&amp;relation=adj_predicate&amp;top=15&amp;min_shared=2</p>
     *
     * <p>Cardinality: requires at least 2 comma-separated {@code seeds} values. For a single
     * seed use {@code /api/semantic-field/explore}.</p>
     *
     * @param exchange the HTTP exchange; receives a 400 if {@code seeds} is missing, has fewer
     *                 than 2 values, uses the unsupported {@code nouns_per} parameter, or the
     *                 relation is unknown
     */
    void handleSemanticFieldExploreMulti(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> params = HttpApiUtils.parseQueryParams(exchange.getRequestURI().getQuery());
            if (params.containsKey("nouns_per")) {
                throw new IllegalArgumentException("nouns_per is not supported for multi-seed exploration");
            }

            String seedsParam = HttpApiUtils.requireParam(params, "seeds");
            RelationConfig resolvedConfig = resolveRelationConfig(params);
            ExplorationOptions opts = parseExplorationOptions(params);
            String format = ExportUtils.parseFormat(params);
            int exportLimit = ExportUtils.parseExportLimit(params);

            Set<String> seeds = parseSeedSet(seedsParam);

            requireAtLeastTwoSeeds(seeds, "Multi-seed exploration");

            ExplorationResult result = explorationService.exploreMultiSeed(seeds, resolvedConfig, opts);

            ExploreResponse response = ExploreResponseAssembler.buildMultiSeedExploreResponse(
                    result, resolvedConfig.id(), opts);

            sendExploreResponse(exchange, response, format, exportLimit, "multi-explore");
        } catch (ExplorationException e) {
            sendExplorationError(exchange, "Multi-seed exploration", e);
        }
    }

    /**
    * Compares relation-scoped collocate profiles for the given seed nouns and returns a
    * graded overlay of shared and distinctive collocates.
     *
     * <p>GET /api/semantic-field/compare?seeds=theory,model,hypothesis&amp;min_logdice=3.0</p>
     *
     * @param exchange the HTTP exchange; receives a 400 if {@code seeds} is missing or has
     *                 fewer than 2 values
     */
    void handleSemanticFieldComparison(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> params = HttpApiUtils.parseQueryParams(exchange.getRequestURI().getQuery());

            String seedsParam = HttpApiUtils.requireParam(params, "seeds");
            RelationConfig resolvedConfig = resolveRelationConfig(params);
            Set<String> seeds = parseSeedSet(seedsParam);
            requireAtLeastTwoSeeds(seeds, "Comparison");

            ExplorationOptions opts = parseExplorationOptions(params);
            String format = ExportUtils.parseFormat(params);
            int exportLimit = ExportUtils.parseExportLimit(params);

            ComparisonResult result = explorationService.compareCollocateProfiles(seeds, resolvedConfig, opts);

            ComparisonResponse response = ComparisonResponseAssembler.buildComparisonResponse(
                new ArrayList<>(result.nouns()), resolvedConfig.id(),
                opts,
                result);

            String context = "compare-" + String.join("-", seeds);
            switch (format) {
                case "csv" -> HttpApiUtils.sendCsvResponse(exchange,
                        ExportUtils.comparisonToCsv(response, exportLimit),
                        ExportUtils.downloadFilename(context, "csv"));
                case "xml" -> HttpApiUtils.sendXmlResponse(exchange,
                        ExportUtils.comparisonToXml(response, exportLimit),
                        ExportUtils.downloadFilename(context, "xml"));
                default -> HttpApiUtils.sendJsonResponse(exchange, response);
            }
        } catch (ExplorationException e) {
            sendExplorationError(exchange, "Semantic field comparison", e);
        }
    }

    /**
     * Fetches concordance lines showing a specific collocate-seed pair in context.
     *
     * <p>GET /api/semantic-field/examples?seed=theory&amp;collocate=good&amp;top=10&amp;relation=adj_predicate</p>
     *
     * <p>Parameter names {@code seed} and {@code collocate} mirror those used by the sibling
     * {@code /api/concordance/examples} endpoint. <strong>Error contract differs from
     * {@code /api/concordance/examples}:</strong> this endpoint returns 400 for an unknown
     * relation (strict — no fallback); {@code /api/concordance/examples} silently falls back
     * to a proximity pattern and returns {@code fallback: true} in the response body.
     * Corpus failures return 503 here vs 500 on the concordance endpoint.</p>
     *
     * @param exchange the HTTP exchange; receives a 400 if {@code seed} or {@code collocate}
     *                 is missing or the relation is unknown
     */
    void handleSemanticFieldExamples(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> params = HttpApiUtils.parseQueryParams(exchange.getRequestURI().getQuery());

            String collocate = HttpApiUtils.requireParam(params, "collocate");
            String seed = HttpApiUtils.requireParam(params, "seed");

            RelationConfig resolvedConfig = resolveRelationConfig(params);

            int top = HttpApiUtils.parseIntParam(params, "top", 10);
            String format = ExportUtils.parseFormat(params);
            int exportLimit = ExportUtils.parseExportLimit(params);

            FetchExamplesResult fetched = explorationService.fetchExamples(
                    seed, collocate, resolvedConfig, new FetchExamplesOptions(top));

            ExamplesResponse response = ExploreResponseAssembler.buildExamplesResponse(
                    new ExploreResponseAssembler.ExamplesContext(
                            seed, collocate, resolvedConfig.id(), fetched.bcqlPattern(), top, false),
                    fetched.examples());

            String context = seed + "-" + collocate + "-examples";
            switch (format) {
                case "csv" -> HttpApiUtils.sendCsvResponse(exchange,
                        ExportUtils.examplesToCsv(response, exportLimit),
                        ExportUtils.downloadFilename(context, "csv"));
                case "xml" -> HttpApiUtils.sendXmlResponse(exchange,
                        ExportUtils.examplesToXml(response, exportLimit),
                        ExportUtils.downloadFilename(context, "xml"));
                default -> HttpApiUtils.sendJsonResponse(exchange, response);
            }
        } catch (ExplorationException e) {
            sendExplorationError(exchange, "Semantic field examples", e);
        }
    }

    /** Sends a 503 response for exploration infrastructure failures. */
    private static void sendExplorationError(HttpExchange exchange, String description,
            ExplorationException e) throws IOException {
        logger.error("{} exploration error", description, e);
        HttpApiUtils.sendError(exchange, 503, "Service unavailable: " + e.getMessage());
    }

    /** Dispatches an {@link ExploreResponse} to the appropriate serialiser based on {@code format}. */
    private static void sendExploreResponse(
            HttpExchange exchange, ExploreResponse response,
            String format, int exportLimit, String context) throws IOException {
        switch (format) {
            case "csv" -> HttpApiUtils.sendCsvResponse(exchange,
                    ExportUtils.exploreToCsv(response, exportLimit),
                    ExportUtils.downloadFilename(context, "csv"));
            case "xml" -> HttpApiUtils.sendXmlResponse(exchange,
                    ExportUtils.exploreToXml(response, exportLimit),
                    ExportUtils.downloadFilename(context, "xml"));
            default -> HttpApiUtils.sendJsonResponse(exchange, response);
        }
    }

    /** Parses a comma-separated seeds parameter into a cleaned, lowercased ordered set. */
    private static Set<String> parseSeedSet(@NonNull String seedsParam) {
        Set<String> seeds = new LinkedHashSet<>();
        for (String s : seedsParam.split(",")) {
            String cleaned = s.trim().toLowerCase();
            if (!cleaned.isEmpty()) seeds.add(cleaned);
        }
        return seeds;
    }

    /**
     * Resolves and validates the relation parameter from request params.
     *
     * @throws IllegalArgumentException if the relation is unknown or misconfigured — caught by
     *         {@link HttpApiUtils#wrapWithErrorHandling} and mapped to 400
     */
    private RelationConfig resolveRelationConfig(Map<String, String> params) {
        String relationId = RelationUtils.resolveRelationAlias(
            params.getOrDefault("relation", "noun_adj_predicates"));
        var relationConfig = grammarConfig.relation(relationId);
        if (relationConfig.isEmpty()) {
            throw new IllegalArgumentException("Unknown relation: " + relationId);
        }
        if (relationConfig.get().relationType().isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid relation config: missing or unrecognised relation_type for '" + relationId + "'");
        }
        return relationConfig.get();
    }

    private ExplorationOptions parseExplorationOptions(Map<String, String> params) {
        int top = HttpApiUtils.parseIntParam(params, "top", 10);
        int minShared = HttpApiUtils.parseIntParam(params, "min_shared", 2);
        double minLogDice = HttpApiUtils.parseDoubleParam(params, "min_logdice", 3.0);
        return new ExplorationOptions(top, minLogDice, minShared);
    }

    private static void requireAtLeastTwoSeeds(java.util.Collection<String> seeds, String context) {
        if (seeds.size() < 2) {
            throw new IllegalArgumentException(
                context + " requires at least 2 seeds; received " + seeds.size());
        }
    }
}

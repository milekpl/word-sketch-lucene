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
import pl.marcinmilkowski.word_sketch.model.FetchExamplesOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.SingleSeedExplorationOptions;
import pl.marcinmilkowski.word_sketch.exploration.ExplorationService;

import java.util.Objects;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final ExplorationService semanticFieldExplorer;

    ExplorationHandlers(ExplorationService semanticFieldExplorer, @NonNull GrammarConfig grammarConfig) {
        this.grammarConfig = Objects.requireNonNull(grammarConfig,
            "grammarConfig must not be null; exploration endpoints require a loaded grammar configuration");
        this.semanticFieldExplorer = semanticFieldExplorer;
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
        Map<String, String> params = HttpApiUtils.parseQueryParams(exchange.getRequestURI().getQuery());
        String seed = HttpApiUtils.requireParam(params, "seed");
        RelationConfig relationConfig = resolveRelationConfig(params);
        CommonExploreParams commonParams = parseCommonExploreParams(params);
        int collocatesPerSeed = HttpApiUtils.parseIntParam(params, "nouns_per", 30);

        SingleSeedExplorationOptions opts = new SingleSeedExplorationOptions(
            new ExplorationOptions(commonParams.topCollocates(), commonParams.minLogDice(), commonParams.minShared()),
            collocatesPerSeed);

        ExplorationResult result = semanticFieldExplorer.exploreByPattern(seed, relationConfig, opts);

        Map<String, Object> extraParams = new HashMap<>();
        extraParams.put("nouns_per", collocatesPerSeed);
        Map<String, Object> response = buildCoreExploreResponse(
            relationConfig.id(), commonParams.topCollocates(), commonParams.minShared(),
            commonParams.minLogDice(), extraParams, Map.of("seed", result.seed()));
        ExploreResponseAssembler.populateExploreResponse(response, result);

        HttpApiUtils.sendJsonResponse(exchange, response);
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
        Map<String, String> params = HttpApiUtils.parseQueryParams(exchange.getRequestURI().getQuery());
        if (params.containsKey("nouns_per")) {
            throw new IllegalArgumentException("nouns_per is not supported for multi-seed exploration");
        }

        String seedsParam = HttpApiUtils.requireParam(params, "seeds");
        RelationConfig resolvedConfig = resolveRelationConfig(params);
        CommonExploreParams commonParams = parseCommonExploreParams(params);

        Set<String> seeds = parseSeedSet(seedsParam);

        if (seeds.size() < 2) {
            throw new IllegalArgumentException(
                "Multi-seed exploration requires at least 2 seeds; received " + seeds.size());
        }

        ExplorationOptions opts = new ExplorationOptions(
            commonParams.topCollocates(), commonParams.minLogDice(), commonParams.minShared());
        ExplorationResult result = semanticFieldExplorer.exploreMultiSeed(seeds, resolvedConfig, opts);

        Map<String, Object> variantFields = new HashMap<>();
        variantFields.put("seeds", new ArrayList<>(seeds));
        variantFields.put("seed_count", seeds.size());
        Map<String, Object> response = buildCoreExploreResponse(
            resolvedConfig.id(), commonParams.topCollocates(), commonParams.minShared(),
            commonParams.minLogDice(), Map.of(), variantFields);

        ExploreResponseAssembler.populateExploreResponse(response, result);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Compares cross-relational collocate profiles for the given seed nouns and returns a
     * graded overlay of shared and distinctive collocates.
     *
     * <p>GET /api/semantic-field/compare?seeds=theory,model,hypothesis&amp;min_logdice=3.0</p>
     *
     * <p>Cardinality: requires at least 2 comma-separated {@code seeds} values; works with 2 or
     * more nouns, enabling pairwise and multi-way adjective profile comparison.
     * This endpoint does not accept a {@code relation} parameter because
     * {@link pl.marcinmilkowski.word_sketch.exploration.CollocateProfileComparator} aggregates
     * collocates across all loaded relations rather than filtering to one relation type.</p>
     *
     * <p><strong>Handler asymmetry note:</strong> unlike {@link #handleSemanticFieldExplore} and
     * {@link #handleSemanticFieldExploreMulti}, this handler intentionally does not share the
     * seed/relation parsing preamble used by the other two because comparison is cross-relational —
     * it aggregates collocates across all relations rather than routing to a specific one.
     * The shared base-parameter extraction ({@code top}, {@code min_shared}, {@code min_logdice})
     * is still performed via {@link #parseCommonExploreParams}.</p>
     *
     * @param exchange the HTTP exchange; receives a 400 if {@code seeds} is missing or has
     *                 fewer than 2 values
     */
    void handleSemanticFieldComparison(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpApiUtils.parseQueryParams(exchange.getRequestURI().getQuery());

        String seedsParam = HttpApiUtils.requireParam(params, "seeds");
        Set<String> seeds = parseSeedSet(seedsParam);
        if (seeds.size() < 2) {
            throw new IllegalArgumentException(
                "Comparison requires at least 2 seed nouns; received " + seeds.size());
        }

        CommonExploreParams commonParams = parseCommonExploreParams(params);

        ExplorationOptions opts = new ExplorationOptions(
            commonParams.topCollocates(), commonParams.minLogDice(), commonParams.minShared());
        ComparisonResult result = semanticFieldExplorer.compareCollocateProfiles(seeds, opts);

        Map<String, Object> variantFields = new HashMap<>();
        variantFields.put("seeds", new ArrayList<>(result.nouns()));
        variantFields.put("seed_count", result.nouns().size());
        Map<String, Object> response = buildCoreExploreResponse(
            "all", commonParams.topCollocates(), commonParams.minShared(),
            commonParams.minLogDice(), Map.of(), variantFields);
        ExploreResponseAssembler.populateComparisonResponse(response, result);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Fetches concordance lines showing a specific collocate-seed pair in context.
     *
     * <p>GET /api/semantic-field/examples?seed=theory&amp;collocate=good&amp;top=10&amp;relation=adj_predicate</p>
     *
     * <p>Parameter names {@code seed} and {@code collocate} mirror those used by the sibling
     * {@code /api/concordance/examples} endpoint.</p>
     *
     * @param exchange the HTTP exchange; receives a 400 if {@code seed} or {@code collocate}
     *                 is missing or the relation is unknown
     */
    void handleSemanticFieldExamples(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpApiUtils.parseQueryParams(exchange.getRequestURI().getQuery());

        String collocate = HttpApiUtils.requireParam(params, "collocate");
        String seed = HttpApiUtils.requireParam(params, "seed");

        int maxExamples = HttpApiUtils.parseIntParam(params, "top", 10);

        RelationConfig resolvedConfig = resolveRelationConfig(params);

        List<String> examples = semanticFieldExplorer.fetchExamples(collocate, seed, resolvedConfig, new FetchExamplesOptions(maxExamples));

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("collocate", collocate);
        response.put("seed", seed);
        response.put("relation", resolvedConfig.id());
        response.put("top", maxExamples);
        response.put("examples", examples);
        response.put("total_results", examples.size());

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Builds the shared core of an explore response: {@code status}, a {@code parameters}
     * sub-map containing the four common explore parameters plus any {@code extraParams}
     * (e.g., {@code nouns_per} for single-seed exploration), and any top-level
     * {@code variantFields} (e.g., {@code seed}, {@code seeds}, {@code seed_count}).
     *
     * <p>Callers pass all variant-specific top-level fields via {@code variantFields} so the
     * full envelope is assembled in a single call rather than spread across multiple
     * {@code response.put()} calls at each callsite.</p>
     */
    private Map<String, Object> buildCoreExploreResponse(
            String relationType, int topCollocates, int minShared, double minLogDice,
            Map<String, Object> extraParams, Map<String, Object> variantFields) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");

        Map<String, Object> paramsUsed = new HashMap<>();
        paramsUsed.put("relation", relationType);
        paramsUsed.put("top", topCollocates);
        paramsUsed.put("min_shared", minShared);
        paramsUsed.put("min_logdice", minLogDice);
        paramsUsed.putAll(extraParams);
        response.put("parameters", paramsUsed);

        response.putAll(variantFields);
        return response;
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
     * Parameters common to all three exploration handlers: {@code top}, {@code min_shared},
     * and {@code min_logdice}. Handler-specific parameters (e.g., {@code nouns_per} for
     * single-seed, rejected {@code nouns_per} for multi-seed) are parsed inline per handler.
     */
    private record CommonExploreParams(int topCollocates, int minShared, double minLogDice) {}

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
        var relType = relationConfig.get().relationType();
        if (relType.isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid relation config: missing or unrecognised relation_type for '" + relationId + "'");
        }
        return relationConfig.get();
    }

    private CommonExploreParams parseCommonExploreParams(Map<String, String> params) {
        int top = HttpApiUtils.parseIntParam(params, "top", 10);
        int minShared = HttpApiUtils.parseIntParam(params, "min_shared", 2);
        double minLogDice = HttpApiUtils.parseDoubleParam(params, "min_logdice", 3.0);
        return new CommonExploreParams(top, minShared, minLogDice);
    }
}

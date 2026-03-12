package pl.marcinmilkowski.word_sketch.api;

import com.sun.net.httpserver.HttpExchange;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.config.RelationUtils;
import pl.marcinmilkowski.word_sketch.model.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.FetchExamplesOptions;
import pl.marcinmilkowski.word_sketch.model.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.SingleSeedExplorationOptions;
import pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer;

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
    private final SemanticFieldExplorer semanticFieldExplorer;

    ExplorationHandlers(@NonNull GrammarConfig grammarConfig, SemanticFieldExplorer semanticFieldExplorer) {
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
        ExplorationParams exploreParams = parseExplorationParams(params);

        // Safe: resolveRelationConfig guarantees relationType is present
        String relationType = relationConfig.relationType().orElseThrow().name();

        SingleSeedExplorationOptions opts = new SingleSeedExplorationOptions(
            new ExplorationOptions(exploreParams.topCollocates(), exploreParams.minLogDice(), exploreParams.minShared()),
            exploreParams.nounsPerCollocate());

        ExplorationResult result = semanticFieldExplorer.exploreByPattern(seed, relationConfig, opts);

        Map<String, Object> extraParams = new HashMap<>();
        extraParams.put("nouns_per", exploreParams.nounsPerCollocate());
        Map<String, Object> response = buildCoreExploreResponse(
            relationType, exploreParams.topCollocates(), exploreParams.minShared(),
            exploreParams.minLogDice(), extraParams);
        response.put("seed", result.seed());
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
        // Parse once and reuse for both the nouns_per pre-flight and request building.
        Map<String, String> params = HttpApiUtils.parseQueryParams(exchange.getRequestURI().getQuery());
        if (params.containsKey("nouns_per")) {
            throw new IllegalArgumentException("nouns_per is not supported for multi-seed exploration");
        }

        String seedsRaw = HttpApiUtils.requireParam(params, "seeds");
        RelationConfig resolvedConfig = resolveRelationConfig(params);
        ExplorationParams exploreParams = parseExplorationParams(params);

        Set<String> seeds = parseSeedSet(seedsRaw);

        if (seeds.size() < 2) {
            throw new IllegalArgumentException(
                "Multi-seed exploration requires at least 2 seeds; received " + seeds.size());
        }

        // Safe: resolveRelationConfig guarantees relationType is present
        String relationType = resolvedConfig.relationType().orElseThrow().name();

        ExplorationOptions opts = new ExplorationOptions(
            exploreParams.topCollocates(), exploreParams.minLogDice(),
            exploreParams.minShared());
        ExplorationResult result = semanticFieldExplorer.exploreMultiSeed(seeds, resolvedConfig, opts);

        Map<String, Object> response = buildCoreExploreResponse(
            relationType, exploreParams.topCollocates(), exploreParams.minShared(),
            exploreParams.minLogDice(), Map.of());
        response.put("seeds", new ArrayList<>(seeds));
        response.put("seed_count", seeds.size());

        ExploreResponseAssembler.populateExploreResponse(response, result);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Compares cross-relational collocate profiles for the given seed nouns and returns a
     * graded overlay of shared and distinctive collocates.
     *
     * <p>GET /api/semantic-field/compare?seeds=theory,model,hypothesis&amp;min_logdice=3.0</p>
     *
     * <p>Also reachable at the legacy path {@code /api/semantic-field}.</p>
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
     * is still performed via {@link #parseExplorationParams}.</p>
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

        ExplorationParams exploreParams = parseExplorationParams(params);

        ExplorationOptions opts = new ExplorationOptions(
            exploreParams.topCollocates(), exploreParams.minLogDice(), exploreParams.minShared());
        ComparisonResult result = semanticFieldExplorer.compareCollocateProfiles(seeds, opts);

        Map<String, Object> response = new HashMap<>();
        ExploreResponseAssembler.populateComparisonResponse(response, result,
            exploreParams.topCollocates(), exploreParams.minLogDice(), exploreParams.minShared());

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
        response.put("examples", examples);
        response.put("total_results", examples.size());

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Builds the shared core of an explore response: {@code status}, {@code relation_type},
     * and a {@code parameters} sub-map containing the four common explore parameters plus
     * any {@code extraParams} (e.g., {@code nouns_per} for single-seed exploration).
     */
    private Map<String, Object> buildCoreExploreResponse(
            String relationType, int topCollocates, int minShared, double minLogDice,
            Map<String, Object> extraParams) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");

        Map<String, Object> paramsUsed = new HashMap<>();
        paramsUsed.put("relation", relationType);
        paramsUsed.put("top", topCollocates);
        paramsUsed.put("min_shared", minShared);
        paramsUsed.put("min_logdice", minLogDice);
        paramsUsed.putAll(extraParams);
        response.put("parameters", paramsUsed);
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

    private record ExplorationParams(int topCollocates, int minShared, double minLogDice, int nounsPerCollocate) {}

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
        return relationConfig.orElseThrow();
    }

    private ExplorationParams parseExplorationParams(Map<String, String> params) {
        int top = HttpApiUtils.parseIntParam(params, "top", 10);
        int minShared = HttpApiUtils.parseIntParam(params, "min_shared", 2);
        double minLogDice = HttpApiUtils.parseDoubleParam(params, "min_logdice", 3.0);
        int nounsPerCollocate = HttpApiUtils.parseIntParam(params, "nouns_per", 30);
        return new ExplorationParams(top, minShared, minLogDice, nounsPerCollocate);
    }
}

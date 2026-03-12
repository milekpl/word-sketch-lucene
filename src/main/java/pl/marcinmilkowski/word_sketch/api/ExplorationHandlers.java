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
import pl.marcinmilkowski.word_sketch.exploration.spi.ExplorationService;
import pl.marcinmilkowski.word_sketch.model.exploration.Seeds;

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
    private final ExplorationService semanticFieldExplorer;

    /** Sentinel emitted in the {@code relation} field of compare-endpoint responses to signal aggregated cross-relational results. */
    private static final String CROSS_RELATIONAL = "cross_relational";

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
        RelationConfig resolvedConfig = resolveRelationConfig(params);
        SharedExploreParams commonParams = parseSharedExploreParams(params);
        int nounsPerSeed = HttpApiUtils.parseIntParam(params, "nouns_per", 30);

        ExplorationOptions base = new ExplorationOptions(
            commonParams.topCollocates(), commonParams.minLogDice(), commonParams.minShared());
        SingleSeedExplorationOptions opts = new SingleSeedExplorationOptions(base, nounsPerSeed);

        ExplorationResult result = semanticFieldExplorer.exploreByRelation(seed, resolvedConfig, opts);

        ExploreResponse response = ExploreResponseAssembler.buildSingleSeedExploreResponse(
                result, resolvedConfig.id(),
                commonParams.topCollocates(), commonParams.minShared(), commonParams.minLogDice(),
                nounsPerSeed);

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
        SharedExploreParams commonParams = parseSharedExploreParams(params);

        Set<String> seeds = parseSeedSet(seedsParam);

        Seeds.requireAtLeastTwo(seeds, "Multi-seed exploration");

        ExplorationOptions opts = new ExplorationOptions(
            commonParams.topCollocates(), commonParams.minLogDice(), commonParams.minShared());
        ExplorationResult result = semanticFieldExplorer.exploreMultiSeed(seeds, resolvedConfig, opts);

        ExploreResponse response = ExploreResponseAssembler.buildMultiSeedExploreResponse(
                result, resolvedConfig.id(),
                commonParams.topCollocates(), commonParams.minShared(), commonParams.minLogDice());

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
     * is still performed via {@link #parseSharedExploreParams}.</p>
     *
     * @param exchange the HTTP exchange; receives a 400 if {@code seeds} is missing or has
     *                 fewer than 2 values
     */
    void handleSemanticFieldComparison(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpApiUtils.parseQueryParams(exchange.getRequestURI().getQuery());

        String seedsParam = HttpApiUtils.requireParam(params, "seeds");
        Set<String> seeds = parseSeedSet(seedsParam);
        Seeds.requireAtLeastTwo(seeds, "Comparison");

        SharedExploreParams commonParams = parseSharedExploreParams(params);

        ExplorationOptions opts = new ExplorationOptions(
            commonParams.topCollocates(), commonParams.minLogDice(), commonParams.minShared());
        ComparisonResult result = semanticFieldExplorer.compareCollocateProfiles(seeds, opts);

        ComparisonResponse response = ExploreResponseAssembler.buildComparisonResponse(
            new ArrayList<>(result.nouns()), CROSS_RELATIONAL,
            commonParams,
            result);

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

        RelationConfig resolvedConfig = resolveRelationConfig(params);

        int top = HttpApiUtils.parseIntParam(params, "top", 10);
        FetchExamplesResult fetched = semanticFieldExplorer.fetchExamples(
                seed, collocate, resolvedConfig, new FetchExamplesOptions(top));

        ExamplesResponse response = ExploreResponseAssembler.buildExamplesResponse(
                new ExploreResponseAssembler.ExamplesContext(
                        seed, collocate, resolvedConfig.id(), fetched.bcqlPattern(), top, null),
                fetched.examples());

        HttpApiUtils.sendJsonResponse(exchange, response);
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

    private SharedExploreParams parseSharedExploreParams(Map<String, String> params) {
        int top = HttpApiUtils.parseIntParam(params, "top", 10);
        int minShared = HttpApiUtils.parseIntParam(params, "min_shared", 2);
        double minLogDice = HttpApiUtils.parseDoubleParam(params, "min_logdice", 3.0);
        return new SharedExploreParams(top, minShared, minLogDice);
    }
}

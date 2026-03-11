package pl.marcinmilkowski.word_sketch.api;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.config.RelationUtils;
import pl.marcinmilkowski.word_sketch.model.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.ExplorationResult;
import pl.marcinmilkowski.word_sketch.exploration.ExploreOptions;
import pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer;

import org.jspecify.annotations.Nullable;

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

    ExplorationHandlers(GrammarConfig grammarConfig, SemanticFieldExplorer semanticFieldExplorer) {
        if (grammarConfig == null) {
            throw new IllegalArgumentException("grammarConfig must not be null; exploration endpoints require a loaded grammar configuration");
        }
        this.grammarConfig = grammarConfig;
        this.semanticFieldExplorer = semanticFieldExplorer;
    }

    /**
     * Parses the query string from the exchange into a parameter map.
     * All four exploration handlers share this identical preamble.
     */
    private static Map<String, String> parseBaseExploreRequest(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        return HttpApiUtils.parseQueryParams(query);
    }

    /**
     * Shared preamble for the three exploration handlers that require a {@code seeds} parameter
     * and a {@code RelationConfig}: parse request, require seeds, resolve relation config, and
     * resolve numeric parameters in one call.
     *
     * @return a populated {@link ValidatedExploreRequest}, or {@code null} if a 400 has already
     *         been sent and the handler should return immediately
     */
    private @Nullable ValidatedExploreRequest validateExploreRequest(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseBaseExploreRequest(exchange);

        String seedsRaw = HttpApiUtils.requireParam(exchange, params, "seeds");
        if (seedsRaw == null) return null;

        RelationConfig resolvedConfig = resolveRelationConfig(exchange, params);
        if (resolvedConfig == null) return null;

        ExploreParams exploreParams = resolveExploreParams(exchange, params);
        if (exploreParams == null) return null;

        return new ValidatedExploreRequest(params, seedsRaw, resolvedConfig, exploreParams);
    }

    private record ValidatedExploreRequest(
            Map<String, String> params,
            String seedsRaw,
            RelationConfig relationConfig,
            ExploreParams exploreParams) {}

    /**
     * Handle semantic field exploration (single seed).
     * GET /api/semantic-field/explore?seeds=house&relation=adj_predicate&top=15&min_shared=2&min_logdice=3.0
     */
    void handleSemanticFieldExplore(HttpExchange exchange) throws IOException {
        ValidatedExploreRequest req = validateExploreRequest(exchange);
        if (req == null) return;

        String seed = req.seedsRaw();
        RelationConfig resolvedConfig = req.relationConfig();
        String relationType = resolvedConfig.relationType().get().name();
        int topCollocates = req.exploreParams().topCollocates();
        int minShared = req.exploreParams().minShared();
        double minLogDice = req.exploreParams().minLogDice();
        int nounsPerCollocate = req.exploreParams().nounsPerCollocate();

        ExploreOptions opts = new ExploreOptions(
            topCollocates, nounsPerCollocate, minLogDice, minShared);

        ExplorationResult result = semanticFieldExplorer.exploreByPattern(seed, resolvedConfig, opts);

        Map<String, Object> extraParams = new HashMap<>();
        extraParams.put("nouns_per", nounsPerCollocate);
        Map<String, Object> response = buildBaseExploreResponse(relationType, topCollocates, minShared, minLogDice, extraParams);
        response.put("seed", result.getSeed());
        ExploreResponseBuilder.populateExploreResponse(response, result);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Handle multi-seed semantic field exploration.
     * GET /api/semantic-field/explore-multi?seeds=theory,model,hypothesis&relation=adj_predicate&top=15&min_shared=2
     */
    void handleSemanticFieldExploreMulti(HttpExchange exchange) throws IOException {
        ValidatedExploreRequest req = validateExploreRequest(exchange);
        if (req == null) return;

        Set<String> seeds = parseSeedSet(req.seedsRaw());

        if (seeds.size() < 2) {
            HttpApiUtils.sendError(exchange, 400, "Need at least 2 seeds for multi-seed exploration");
            return;
        }

        RelationConfig resolvedConfig = req.relationConfig();
        String relationType = resolvedConfig.relationType().get().name();

        if (req.params().containsKey("nouns_per")) {
            HttpApiUtils.sendError(exchange, 400, "nouns_per is not supported for multi-seed exploration");
            return;
        }

        int topCollocates = req.exploreParams().topCollocates();
        int minShared = req.exploreParams().minShared();
        double minLogDice = req.exploreParams().minLogDice();

        ExploreOptions opts = ExploreOptions.forMultiSeed(topCollocates, minLogDice, minShared);
        ExplorationResult result = semanticFieldExplorer.exploreMultiSeed(seeds, resolvedConfig, opts);

        Map<String, Object> response = buildBaseExploreResponse(relationType, topCollocates, minShared, minLogDice, Map.of());
        response.put("seeds", new ArrayList<>(seeds));
        response.put("seed_count", seeds.size());

        ExploreResponseBuilder.populateExploreResponse(response, result);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Handle semantic field comparison.
     * GET /api/semantic-field?seeds=theory,model,hypothesis&min_logdice=3.0
     */
    void handleSemanticFieldComparison(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseBaseExploreRequest(exchange);

        String seedsParam = HttpApiUtils.requireParam(exchange, params, "seeds");
        if (seedsParam == null) return;

        Set<String> seeds = parseSeedSet(seedsParam);

        ExploreParams exploreParams = resolveExploreParams(exchange, params);
        if (exploreParams == null) return;
        int topCollocates = exploreParams.topCollocates();
        double minLogDice = exploreParams.minLogDice();

        ComparisonResult result = semanticFieldExplorer.compareCollocateProfiles(seeds, minLogDice, topCollocates);

        Map<String, Object> response = new HashMap<>();
        ExploreResponseBuilder.populateComparisonResponse(response, result, seeds, topCollocates, minLogDice);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Handle semantic field examples.
     * GET /api/semantic-field/examples?adjective=good&noun=theory&max=10&relation=adj_predicate
     */
    void handleSemanticFieldExamples(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseBaseExploreRequest(exchange);

        String adjective = HttpApiUtils.requireParam(exchange, params, "adjective");
        if (adjective == null) return;
        String noun = HttpApiUtils.requireParam(exchange, params, "noun");
        if (noun == null) return;

        int maxExamples = parseIntParam(exchange, params, "top", 10);
        if (maxExamples < 0) return;

        RelationConfig resolvedConfig = resolveRelationConfig(exchange, params);
        if (resolvedConfig == null) return;

        List<String> examples = semanticFieldExplorer.fetchExamples(adjective, noun, resolvedConfig, maxExamples);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("adjective", adjective);
        response.put("noun", noun);
        response.put("examples", examples);
        response.put("total_results", examples.size());

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Builds the shared base of an explore response: {@code status}, {@code relation_type},
     * and a {@code parameters} sub-map containing the four common explore parameters plus
     * any {@code extraParams} (e.g., {@code nouns_per} for single-seed exploration).
     */
    private Map<String, Object> buildBaseExploreResponse(
            String relationType, int topCollocates, int minShared, double minLogDice,
            Map<String, Object> extraParams) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("relation_type", relationType);

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
    private static Set<String> parseSeedSet(String seedsParam) {
        Set<String> seeds = new LinkedHashSet<>();
        if (seedsParam != null) {
            for (String s : seedsParam.split(",")) {
                String cleaned = s.trim().toLowerCase();
                if (!cleaned.isEmpty()) seeds.add(cleaned);
            }
        }
        return seeds;
    }

    private record ExploreParams(int topCollocates, int minShared, double minLogDice, int nounsPerCollocate) {}

    /**
     * Resolves and validates the relation parameter from request params.
     * Sends a 400 error response and returns null if the relation is unknown or misconfigured.
     * Both exploration handlers share this preamble.
     */
    private @Nullable RelationConfig resolveRelationConfig(HttpExchange exchange, Map<String, String> params) throws IOException {
        String relationId = RelationUtils.resolveRelationAlias(
            params.getOrDefault("relation", "noun_adj_predicates"));
        var relationConfig = grammarConfig.getRelation(relationId);
        if (relationConfig.isEmpty()) {
            HttpApiUtils.sendError(exchange, 400, "Unknown relation: " + relationId);
            return null;
        }
        var relType = relationConfig.get().relationType();
        if (relType.isEmpty()) {
            HttpApiUtils.sendError(exchange, 400,
                "Invalid relation config: missing or unrecognised relation_type for '" + relationId + "'");
            return null;
        }
        return relationConfig.get();
    }

    private @Nullable ExploreParams resolveExploreParams(HttpExchange exchange, Map<String, String> params) throws IOException {
        int top = parseIntParam(exchange, params, "top", 10);
        if (top < 0) return null;
        int minShared = parseIntParam(exchange, params, "min_shared", 2);
        if (minShared < 0) return null;
        double minLogDice = parseDoubleParam(exchange, params, "min_logdice", 3.0);
        if (minLogDice < 0) return null;
        int nounsPerCollocate = parseIntParam(exchange, params, "nouns_per", 30);
        if (nounsPerCollocate < 0) return null;
        return new ExploreParams(top, minShared, minLogDice, nounsPerCollocate);
    }

    /** Parses an int parameter by name; sends 400 and returns -1 on parse failure. */
    private int parseIntParam(HttpExchange exchange, Map<String, String> params,
                              String name, int defaultValue) throws IOException {
        String raw = params.getOrDefault(name, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            HttpApiUtils.sendError(exchange, 400, "Invalid numeric parameter '" + name + "': expected integer, got: '" + raw + "'");
            return -1;
        }
    }

    /** Parses a double parameter by name; sends 400 and returns -1 on parse failure. */
    private double parseDoubleParam(HttpExchange exchange, Map<String, String> params,
                                    String name, double defaultValue) throws IOException {
        String raw = params.getOrDefault(name, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            HttpApiUtils.sendError(exchange, 400, "Invalid numeric parameter '" + name + "': expected decimal, got: '" + raw + "'");
            return -1;
        }
    }
}

package pl.marcinmilkowski.word_sketch.api;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.exploration.Edge;
import pl.marcinmilkowski.word_sketch.exploration.ExploreOptions;
import pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer;
import pl.marcinmilkowski.word_sketch.model.AdjectiveProfile;
import pl.marcinmilkowski.word_sketch.model.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.model.ExplorationResult;

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
     * Handle semantic field exploration (single seed).
     * GET /api/semantic-field/explore?seeds=house&relation=adj_predicate&top=15&min_shared=2&min_logdice=3.0
     */
    void handleSemanticFieldExplore(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseBaseExploreRequest(exchange);

        String seed = HttpApiUtils.requireParam(exchange, params, "seeds");
        if (seed == null) return;

        RelationConfig resolvedConfig = resolveRelationConfig(exchange, params);
        if (resolvedConfig == null) return;

        String relationType = resolvedConfig.relationType().orElseThrow().name();

        ExploreParams exploreParams = resolveExploreParams(exchange, params);
        if (exploreParams == null) return;
        int topCollocates = exploreParams.topCollocates();
        int minShared = exploreParams.minShared();
        double minLogDice = exploreParams.minLogDice();
        int nounsPerSeed = exploreParams.nounsPerSeed();

        ExploreOptions opts = new ExploreOptions(
            topCollocates, nounsPerSeed, minLogDice, minShared);

        ExplorationResult result;
        try {
            result = semanticFieldExplorer.exploreByPattern(seed, resolvedConfig, opts);
        } catch (IOException e) {
            HttpApiUtils.sendError(exchange, 500, "Exploration failed: " + e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            HttpApiUtils.sendError(exchange, 400, "Invalid exploration parameters: " + e.getMessage());
            return;
        }

        Map<String, Object> extraParams = new HashMap<>();
        extraParams.put("nouns_per", nounsPerSeed);
        Map<String, Object> response = buildBaseExploreResponse(relationType, topCollocates, minShared, minLogDice, extraParams);
        response.put("seed", result.getSeed());
        buildExploreResponseBody(response, result);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Handle multi-seed semantic field exploration.
     * GET /api/semantic-field/explore-multi?seeds=theory,model,hypothesis&relation=adj_predicate&top=15&min_shared=2
     */
    void handleSemanticFieldExploreMulti(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseBaseExploreRequest(exchange);

        String seedsStr = HttpApiUtils.requireParam(exchange, params, "seeds");
        if (seedsStr == null) return;

        Set<String> seeds = parseSeedSet(seedsStr);

        if (seeds.size() < 2) {
            HttpApiUtils.sendError(exchange, 400, "Need at least 2 seeds for multi-seed exploration");
            return;
        }

        RelationConfig resolvedConfig = resolveRelationConfig(exchange, params);
        if (resolvedConfig == null) return;

        String relationType = resolvedConfig.relationType().orElseThrow().name();

        if (params.containsKey("nouns_per")) {
            HttpApiUtils.sendError(exchange, 400, "nouns_per is not supported for multi-seed exploration");
            return;
        }

        ExploreParams exploreParams = resolveExploreParams(exchange, params);
        if (exploreParams == null) return;
        int topCollocates = exploreParams.topCollocates();
        int minShared = exploreParams.minShared();
        double minLogDice = exploreParams.minLogDice();

        ExplorationResult result;
        try {
            result = semanticFieldExplorer.exploreMultiSeed(
                seeds, resolvedConfig, minLogDice, topCollocates, minShared);
        } catch (IOException e) {
            HttpApiUtils.sendError(exchange, 500, "Multi-seed exploration failed: " + e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            HttpApiUtils.sendError(exchange, 400, "Invalid exploration parameters: " + e.getMessage());
            return;
        }

        Map<String, Object> response = buildBaseExploreResponse(relationType, topCollocates, minShared, minLogDice, Map.of());
        response.put("seeds", new ArrayList<>(seeds));
        response.put("seed_count", seeds.size());

        buildExploreResponseBody(response, result);

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

        ComparisonResult result;
        try {
            result = semanticFieldExplorer.compareCollocateProfiles(seeds, minLogDice, topCollocates);
        } catch (IOException e) {
            HttpApiUtils.sendError(exchange, 500, "Comparison failed: " + e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            HttpApiUtils.sendError(exchange, 400, "Invalid comparison parameters: " + e.getMessage());
            return;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("seeds", new ArrayList<>(result.getNouns()));
        response.put("seed_count", seeds.size());

        Map<String, Object> paramsUsed = new HashMap<>();
        paramsUsed.put("top", topCollocates);
        paramsUsed.put("min_logdice", minLogDice);
        response.put("parameters", paramsUsed);

        List<Map<String, Object>> adjectives = new ArrayList<>();
        for (AdjectiveProfile adj : result.getAllAdjectives()) {
            adjectives.add(formatAdjectiveProfile(adj));
        }
        response.put("adjectives", adjectives);
        response.put("adjectives_count", result.getAllAdjectives().size());
        response.put("fully_shared_count", result.getFullyShared().size());
        response.put("partially_shared_count", result.getPartiallyShared().size());
        response.put("specific_count", result.getSpecific().size());

        List<Edge> edges = ExploreResponseBuilder.buildEdges(result);
        response.put("edges", edges.stream().map(Edge::toMap).toList());
        response.put("edges_count", edges.size());

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

        int maxExamples;
        try {
            maxExamples = Integer.parseInt(params.getOrDefault("max", "10"));
        } catch (NumberFormatException e) {
            HttpApiUtils.sendError(exchange, 400, "Invalid numeric parameter: max");
            return;
        }

        RelationConfig resolvedConfig = resolveRelationConfig(exchange, params);
        if (resolvedConfig == null) return;

        List<String> examples;
        try {
            examples = semanticFieldExplorer.fetchExamples(adjective, noun, resolvedConfig, maxExamples);
        } catch (IOException e) {
            HttpApiUtils.sendError(exchange, 500, "Failed to fetch examples: " + e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            HttpApiUtils.sendError(exchange, 400, "Invalid parameters: " + e.getMessage());
            return;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("adjective", adjective);
        response.put("noun", noun);
        response.put("examples", examples);
        response.put("total_results", examples.size());

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    private void buildExploreResponseBody(Map<String, Object> response, ExplorationResult result) {
        List<Map<String, Object>> seedCollocs = new ArrayList<>();
        for (Map.Entry<String, Double> e : result.getSeedCollocates().entrySet()) {
            Map<String, Object> c = new HashMap<>();
            c.put("word", e.getKey());
            c.put("log_dice", round2dp(e.getValue()));
            c.put("frequency", result.getSeedCollocateFrequencies().getOrDefault(e.getKey(), 0L));
            seedCollocs.add(c);
        }
        response.put("seed_collocates", seedCollocs);
        response.put("seed_collocates_count", seedCollocs.size());

        List<Map<String, Object>> nouns = new ArrayList<>();
        for (DiscoveredNoun n : result.getDiscoveredNouns()) {
            Map<String, Object> nm = new HashMap<>();
            nm.put("word", n.noun());
            nm.put("shared_count", n.sharedCount());
            nm.put("similarity_score", round2dp(n.combinedRelevanceScore()));
            nm.put("avg_logdice", round2dp(n.avgLogDice()));
            nm.put("shared_collocates", n.sharedCollocateList());
            nouns.add(nm);
        }
        response.put("discovered_nouns", nouns);
        response.put("discovered_nouns_count", nouns.size());

        List<Map<String, Object>> coreCollocs = new ArrayList<>();
        for (CoreCollocate c : result.getCoreCollocates()) {
            Map<String, Object> cm = new HashMap<>();
            cm.put("word", c.collocate());
            cm.put("shared_by_count", c.sharedByCount());
            cm.put("total_nouns", c.totalNouns());
            cm.put("coverage", round2dp(c.coverage()));
            cm.put("seed_logdice", round2dp(c.seedLogDice()));
            coreCollocs.add(cm);
        }
        response.put("core_collocates", coreCollocs);
        response.put("core_collocates_count", coreCollocs.size());

        List<Edge> edges = ExploreResponseBuilder.buildEdges(result);
        response.put("edges", edges.stream().map(Edge::toMap).toList());
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

    /**
     * Serialises a single {@link AdjectiveProfile} into the JSON-compatible map that the
     * {@code /api/semantic-field} endpoint returns for each adjective entry.
     */
    private static Map<String, Object> formatAdjectiveProfile(AdjectiveProfile adj) {
        Map<String, Object> adjMap = new HashMap<>();
        adjMap.put("word", adj.adjective());
        adjMap.put("present_in", adj.presentInCount());
        adjMap.put("total_nouns", adj.totalNouns());
        adjMap.put("avg_logdice", round2dp(adj.avgLogDice()));
        adjMap.put("max_logdice", round2dp(adj.maxLogDice()));
        adjMap.put("variance", round2dp(adj.variance()));
        adjMap.put("commonality_score", round2dp(adj.commonalityScore()));
        adjMap.put("distinctiveness_score", round2dp(adj.distinctivenessScore()));

        String category = adj.isFullyShared() ? "fully_shared"
            : adj.isPartiallyShared() ? "partially_shared" : "specific";
        adjMap.put("category", category);

        Map<String, Double> scores = new HashMap<>();
        if (adj.nounScores() != null) {
            for (Map.Entry<String, Double> entry : adj.nounScores().entrySet()) {
                scores.put(entry.getKey(), round2dp(entry.getValue()));
            }
        }
        adjMap.put("noun_scores", scores);

        if (adj.isSpecific()) {
            adj.strongestNoun().ifPresent(n -> adjMap.put("specific_to", n));
        }
        return adjMap;
    }

    private static double round2dp(double value) {
        return Math.round(value * 100.0) / 100.0;
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

    private record ExploreParams(int topCollocates, int minShared, double minLogDice, int nounsPerSeed) {}

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
        try {
            int top = Integer.parseInt(params.getOrDefault("top", "10"));
            int minShared = Integer.parseInt(params.getOrDefault("min_shared", "2"));
            double minLogDice = Double.parseDouble(params.getOrDefault("min_logdice", "3.0"));
            int nounsPerSeed = Integer.parseInt(params.getOrDefault("nouns_per", "30"));
            return new ExploreParams(top, minShared, minLogDice, nounsPerSeed);
        } catch (NumberFormatException e) {
            HttpApiUtils.sendError(exchange, 400, "Invalid numeric parameter: " + e.getMessage());
            return null;
        }
    }
}

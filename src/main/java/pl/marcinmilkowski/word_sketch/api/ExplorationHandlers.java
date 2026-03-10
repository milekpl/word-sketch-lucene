package pl.marcinmilkowski.word_sketch.api;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader;
import pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer;
import pl.marcinmilkowski.word_sketch.model.AdjectiveProfile;
import pl.marcinmilkowski.word_sketch.model.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.model.Edge;
import pl.marcinmilkowski.word_sketch.model.ExploreOptions;
import pl.marcinmilkowski.word_sketch.model.ExplorationResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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

    private final GrammarConfigLoader grammarConfig;
    private final SemanticFieldExplorer semanticFieldExplorer;

    ExplorationHandlers(GrammarConfigLoader grammarConfig, SemanticFieldExplorer semanticFieldExplorer) {
        if (grammarConfig == null) {
            throw new IllegalArgumentException("grammarConfig must not be null; exploration endpoints require a loaded grammar configuration");
        }
        this.grammarConfig = grammarConfig;
        this.semanticFieldExplorer = semanticFieldExplorer;
    }

    /**
     * Handle semantic field exploration (single seed).
     * GET /api/semantic-field/explore?seed=house&relation=adj_predicate&top=15&min_shared=2&min_logdice=3.0
     */
    void handleSemanticFieldExplore(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HttpApiUtils.parseQueryParams(query);

        String seed = HttpApiUtils.requireParam(exchange, params, "seed");
        if (seed == null) return;

        String relationId = RelationUtils.resolveRelationAlias(params.getOrDefault("relation", "noun_adj_predicates").toLowerCase());

        var relationConfig = grammarConfig.getRelation(relationId);
        if (relationConfig.isEmpty()) {
            HttpApiUtils.sendError(exchange, 400, "Unknown relation: " + relationId);
            return;
        }

        String relationType = relationConfig.get().relationType().name();
        String relationName = relationConfig.get().name();

        ExploreParams ep = parseExploreParams(exchange, params);
        if (ep == null) return;
        int topCollocates = ep.topCollocates();
        int minShared = ep.minShared();
        double minLogDice = ep.minLogDice();
        int nounsPerCollocate = ep.nounsPerSeed();

        ExploreOptions opts = new ExploreOptions(
            topCollocates, nounsPerCollocate, minLogDice, minShared, false);

        ExplorationResult result;
        try {
            result = semanticFieldExplorer.exploreByPattern(seed, relationConfig.get(), opts);
        } catch (IOException e) {
            HttpApiUtils.sendError(exchange, 500, "Exploration failed: " + e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            HttpApiUtils.sendError(exchange, 400, "Invalid exploration parameters: " + e.getMessage());
            return;
        }

        Map<String, Object> extraParams = new HashMap<>();
        extraParams.put("nouns_per", nounsPerCollocate);
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
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HttpApiUtils.parseQueryParams(query);

        String seedsStr = HttpApiUtils.requireParam(exchange, params, "seeds");
        if (seedsStr == null) return;

        String[] seedArray = seedsStr.split(",");
        Set<String> seeds = new LinkedHashSet<>();
        for (String s : seedArray) {
            String cleaned = s.trim().toLowerCase();
            if (!cleaned.isEmpty()) {
                seeds.add(cleaned);
            }
        }

        if (seeds.size() < 2) {
            HttpApiUtils.sendError(exchange, 400, "Need at least 2 seeds for multi-seed exploration");
            return;
        }

        String relationId = RelationUtils.resolveRelationAlias(params.getOrDefault("relation", "noun_adj_predicates").toLowerCase());

        var relationConfig = grammarConfig.getRelation(relationId);
        if (relationConfig.isEmpty()) {
            HttpApiUtils.sendError(exchange, 400, "Unknown relation: " + relationId);
            return;
        }

        String relationType = relationConfig.get().relationType().name();

        ExploreParams ep = parseExploreParams(exchange, params);
        if (ep == null) return;
        int topCollocates = ep.topCollocates();
        int minShared = ep.minShared();
        double minLogDice = ep.minLogDice();
        // nouns_per intentionally not supported in multi-seed mode — seeds parameter is required instead

        ExplorationResult result;
        try {
            result = semanticFieldExplorer.exploreMultiSeed(
                seeds, relationConfig.get(), minLogDice, topCollocates, minShared);
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
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HttpApiUtils.parseQueryParams(query);

        String nounsParam = HttpApiUtils.requireParam(exchange, params, "seeds");
        if (nounsParam == null) return;

        Set<String> nouns = new LinkedHashSet<>(Arrays.asList(nounsParam.split(",")));

        double minLogDice;
        int maxPerNoun;
        try {
            minLogDice = Double.parseDouble(params.getOrDefault("min_logdice", "3.0"));
            maxPerNoun = Integer.parseInt(params.getOrDefault("max_per_noun", "50"));
        } catch (NumberFormatException e) {
            HttpApiUtils.sendError(exchange, 400, "Invalid numeric parameter: " + e.getMessage());
            return;
        }

        ComparisonResult result;
        try {
            result = semanticFieldExplorer.compareCollocateProfiles(nouns, minLogDice, maxPerNoun);
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
        response.put("min_logdice", minLogDice);

        List<Map<String, Object>> adjectives = new ArrayList<>();
        for (AdjectiveProfile adj : result.getAllAdjectives()) {
            adjectives.add(formatAdjectiveProfile(adj));
        }
        response.put("adjectives", adjectives);
        response.put("total_adjectives", result.getAllAdjectives().size());
        response.put("fully_shared", result.getFullyShared().size());
        response.put("partially_shared", result.getPartiallyShared().size());
        response.put("specific", result.getSpecific().size());

        List<Map<String, Object>> edges = new ArrayList<>();
        if (result.getEdges() != null) {
            for (Edge edge : result.getEdges()) {
                edges.add(formatEdge(edge));
            }
        }
        response.put("edges", edges);
        response.put("total_edges", edges.size());

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Handle semantic field examples.
     * GET /api/semantic-field/examples?adjective=good&noun=theory&max=10
     */
    void handleSemanticFieldExamples(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HttpApiUtils.parseQueryParams(query);

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

        List<String> examples;
        try {
            examples = semanticFieldExplorer.fetchExamples(adjective, noun, maxExamples);
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
        response.put("count", examples.size());

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    private static Map<String, Object> formatSeedCollocate(String word, double logDice, long frequency) {
        Map<String, Object> m = new HashMap<>();
        m.put("word", word);
        m.put("log_dice", Math.round(logDice * 100.0) / 100.0);
        m.put("frequency", frequency);
        return m;
    }

    private static Map<String, Object> formatDiscoveredNoun(DiscoveredNoun dn) {
        Map<String, Object> m = new HashMap<>();
        m.put("word", dn.noun());
        m.put("shared_count", dn.sharedCount());
        m.put("similarity_score", Math.round(dn.similarityScore() * 100.0) / 100.0);
        m.put("avg_logdice", Math.round(dn.avgLogDice() * 100.0) / 100.0);
        m.put("shared_collocates", dn.sharedCollocateList());
        return m;
    }

    private static Map<String, Object> formatCoreCollocate(CoreCollocate ca) {
        Map<String, Object> m = new HashMap<>();
        m.put("word", ca.collocate());
        m.put("shared_by_count", ca.sharedByCount());
        m.put("total_nouns", ca.totalNouns());
        m.put("coverage", Math.round(ca.coverage() * 100.0) / 100.0);
        m.put("seed_logdice", Math.round(ca.seedLogDice() * 100.0) / 100.0);
        return m;
    }

    private static Map<String, Object> formatEdge(Edge edge) {
        Map<String, Object> m = new HashMap<>();
        m.put("source", edge.source());
        m.put("target", edge.target());
        m.put("log_dice", Math.round(edge.weight() * 100.0) / 100.0);
        m.put("type", edge.type());
        return m;
    }

    /**
     * Populates the four standard explore response sections — seed_collocates, discovered_nouns,
     * core_collocates, and edges — from {@code result} into {@code response}.
     * Both single-seed and multi-seed handlers call this after setting their seed/seeds key.
     */
    private void buildExploreResponseBody(Map<String, Object> response, ExplorationResult result) {
        List<Map<String, Object>> seedCollocs = new ArrayList<>();
        for (Map.Entry<String, Double> colloc : result.getSeedCollocates().entrySet()) {
            long freq = result.getSeedCollocateFrequencies().getOrDefault(colloc.getKey(), 0L);
            seedCollocs.add(formatSeedCollocate(colloc.getKey(), colloc.getValue(), freq));
        }
        response.put("seed_collocates", seedCollocs);
        response.put("seed_collocates_count", seedCollocs.size());

        List<Map<String, Object>> nouns = new ArrayList<>();
        for (DiscoveredNoun dn : result.getDiscoveredNouns()) {
            nouns.add(formatDiscoveredNoun(dn));
        }
        response.put("discovered_nouns", nouns);
        response.put("discovered_nouns_count", nouns.size());

        List<Map<String, Object>> coreCollocs = new ArrayList<>();
        for (CoreCollocate ca : result.getCoreCollocates()) {
            coreCollocs.add(formatCoreCollocate(ca));
        }
        response.put("core_collocates", coreCollocs);
        response.put("core_collocates_count", coreCollocs.size());

        List<Map<String, Object>> edges = new ArrayList<>();
        for (Edge edge : result.getEdges()) {
            edges.add(formatEdge(edge));
        }
        response.put("edges", edges);
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
        adjMap.put("avg_logdice", Math.round(adj.avgLogDice() * 100.0) / 100.0);
        adjMap.put("max_logdice", Math.round(adj.maxLogDice() * 100.0) / 100.0);
        adjMap.put("variance", Math.round(adj.variance() * 100.0) / 100.0);
        adjMap.put("commonality_score", Math.round(adj.commonalityScore() * 100.0) / 100.0);
        adjMap.put("distinctiveness_score", Math.round(adj.distinctivenessScore() * 100.0) / 100.0);

        String category = adj.isFullyShared() ? "fully_shared"
            : adj.isPartiallyShared() ? "partially_shared" : "specific";
        adjMap.put("category", category);

        Map<String, Double> scores = new HashMap<>();
        if (adj.nounScores() != null) {
            for (Map.Entry<String, Double> entry : adj.nounScores().entrySet()) {
                scores.put(entry.getKey(), Math.round(entry.getValue() * 100.0) / 100.0);
            }
        }
        adjMap.put("noun_scores", scores);

        if (adj.isSpecific()) {
            adjMap.put("specific_to", adj.strongestNoun());
        }
        return adjMap;
    }

    private record ExploreParams(int topCollocates, int minShared, double minLogDice, int nounsPerSeed) {}

    private ExploreParams parseExploreParams(HttpExchange exchange, Map<String, String> params) throws IOException {
        try {
            int top = Integer.parseInt(params.getOrDefault("top", "15"));
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

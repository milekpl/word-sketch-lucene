package pl.marcinmilkowski.word_sketch.api;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.QueryResults;
import pl.marcinmilkowski.word_sketch.utils.PosGroup;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer;
// TODO(42e2a9a1): These imports bind the API layer to SEF's inner result classes.
// Once SEF inner classes are moved to a dedicated model/ package, update these imports.
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.ComparisonResult;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.AdjectiveProfile;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.ExplorationResult;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.CoreCollocate;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.ExploreOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP handlers for semantic field exploration endpoints.
 */
class ExplorationHandlers {

    private static final Logger logger = LoggerFactory.getLogger(ExplorationHandlers.class);

    private final QueryExecutor executor;
    private final GrammarConfigLoader grammarConfig;
    private final SemanticFieldExplorer semanticFieldExplorer;

    ExplorationHandlers(QueryExecutor executor, GrammarConfigLoader grammarConfig, SemanticFieldExplorer semanticFieldExplorer) {
        this.executor = executor;
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

        String seed = params.getOrDefault("seed", "");
        if (seed.isEmpty()) {
            HttpApiUtils.sendError(exchange, 400, "Missing required parameter: seed");
            return;
        }

        String relationId = resolveRelationAlias(params.getOrDefault("relation", "noun_adj_predicates").toLowerCase());

        if (grammarConfig == null) {
            HttpApiUtils.sendError(exchange, 500, "Grammar configuration not loaded");
            return;
        }

        var relationConfig = grammarConfig.getRelation(relationId);
        if (relationConfig.isEmpty()) {
            HttpApiUtils.sendError(exchange, 400, "Unknown relation: " + relationId);
            return;
        }

        String relationType = relationConfig.get().relationType().name();
        String relationName = relationConfig.get().name();

        int topCollocates;
        int nounsPerCollocate;
        int minShared;
        double minLogDice;
        try {
            topCollocates = Integer.parseInt(params.getOrDefault("top", "15"));
            nounsPerCollocate = Integer.parseInt(params.getOrDefault("nouns_per", "30"));
            minShared = Integer.parseInt(params.getOrDefault("min_shared", "2"));
            minLogDice = Double.parseDouble(params.getOrDefault("min_logdice", "3.0"));
        } catch (NumberFormatException e) {
            HttpApiUtils.sendError(exchange, 400, "Invalid numeric parameter: " + e.getMessage());
            return;
        }

        String bcqlPattern = relationConfig.get().getFullPattern(seed);
        String simplePattern = relationConfig.get().collocatePosGroup() == PosGroup.ADJ ?
            "[xpos=\"JJ.*\"]" : "[xpos=\"NN.*|VB.*\"]";
        int headPos = relationConfig.get().headPosition();
        int collocatePos = relationConfig.get().collocatePosition();

        ExploreOptions opts = new ExploreOptions(
            topCollocates, nounsPerCollocate, minLogDice, minShared, false, headPos, collocatePos, null);
        ExplorationResult result = semanticFieldExplorer.exploreByPattern(
            seed, relationName, bcqlPattern, simplePattern, opts);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("seed", result.seed);
        response.put("relation_type", relationType);

        Map<String, Object> paramsUsed = new HashMap<>();
        paramsUsed.put("relation", relationType);
        paramsUsed.put("top", topCollocates);
        paramsUsed.put("nouns_per", nounsPerCollocate);
        paramsUsed.put("min_shared", minShared);
        paramsUsed.put("min_logdice", minLogDice);
        response.put("parameters", paramsUsed);

        List<Map<String, Object>> seedCollocs = new ArrayList<>();
        if (result.seedCollocates != null) {
            for (Map.Entry<String, Double> colloc : result.seedCollocates.entrySet()) {
                Map<String, Object> collocMap = new HashMap<>();
                collocMap.put("word", colloc.getKey());
                collocMap.put("logDice", Math.round(colloc.getValue() * 100.0) / 100.0);
                seedCollocs.add(collocMap);
            }
        }
        response.put("seed_collocates", seedCollocs);
        response.put("seed_collocates_count", seedCollocs.size());

        List<Map<String, Object>> nouns = new ArrayList<>();
        if (result.discoveredNouns != null) {
            for (DiscoveredNoun dn : result.discoveredNouns) {
                Map<String, Object> nounMap = new HashMap<>();
                nounMap.put("word", dn.noun);
                nounMap.put("shared_count", dn.sharedCount);
                nounMap.put("similarity_score", Math.round(dn.similarityScore * 100.0) / 100.0);
                nounMap.put("avg_logdice", Math.round(dn.avgLogDice * 100.0) / 100.0);
                nounMap.put("shared_collocates", dn.getSharedCollocateList());
                nouns.add(nounMap);
            }
        }
        response.put("discovered_nouns", nouns);
        response.put("discovered_nouns_count", nouns.size());

        List<Map<String, Object>> coreCollocs = new ArrayList<>();
        if (result.coreCollocates != null) {
            for (CoreCollocate ca : result.coreCollocates) {
                Map<String, Object> collocMap = new HashMap<>();
                collocMap.put("word", ca.collocate);
                collocMap.put("shared_by_count", ca.sharedByCount);
                collocMap.put("total_nouns", ca.totalNouns);
                collocMap.put("coverage", Math.round(ca.getCoverage() * 100.0) / 100.0);
                collocMap.put("seed_logdice", Math.round(ca.seedLogDice * 100.0) / 100.0);
                coreCollocs.add(collocMap);
            }
        }
        response.put("core_collocates", coreCollocs);
        response.put("core_collocates_count", coreCollocs.size());

        List<Map<String, Object>> edges = new ArrayList<>();
        if (result.getEdges() != null) {
            for (SemanticFieldExplorer.Edge edge : result.getEdges()) {
                Map<String, Object> edgeMap = new HashMap<>();
                edgeMap.put("source", edge.source);
                edgeMap.put("target", edge.target);
                edgeMap.put("logDice", Math.round(edge.weight * 100.0) / 100.0);
                edgeMap.put("type", edge.type);
                edges.add(edgeMap);
            }
        }
        response.put("edges", edges);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Handle multi-seed semantic field exploration.
     * GET /api/semantic-field/explore-multi?seeds=theory,model,hypothesis&relation=adj_predicate&top=15&min_shared=2
     */
    void handleSemanticFieldExploreMulti(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HttpApiUtils.parseQueryParams(query);

        String seedsStr = params.getOrDefault("seeds", "");
        if (seedsStr.isEmpty()) {
            HttpApiUtils.sendError(exchange, 400, "Missing required parameter: seeds (comma-separated)");
            return;
        }

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

        String relationId = resolveRelationAlias(params.getOrDefault("relation", "noun_adj_predicates").toLowerCase());

        if (grammarConfig == null) {
            HttpApiUtils.sendError(exchange, 500, "Grammar configuration not loaded");
            return;
        }

        var relationConfig = grammarConfig.getRelation(relationId);
        if (relationConfig.isEmpty()) {
            HttpApiUtils.sendError(exchange, 400, "Unknown relation: " + relationId);
            return;
        }

        String relationType = relationConfig.get().relationType().name();

        int topCollocates;
        int minShared;
        double minLogDice;
        try {
            topCollocates = Integer.parseInt(params.getOrDefault("top", "15"));
            minShared = Integer.parseInt(params.getOrDefault("min_shared", "2"));
            minLogDice = Double.parseDouble(params.getOrDefault("min_logdice", "3.0"));
        } catch (NumberFormatException e) {
            HttpApiUtils.sendError(exchange, 400, "Invalid numeric parameter: " + e.getMessage());
            return;
        }

        int headPos = relationConfig.get().headPosition();
        int collocatePos = relationConfig.get().collocatePosition();

        Map<String, List<QueryResults.WordSketchResult>> seedToCollocates = new HashMap<>();
        Set<String> commonCollocates = null;

        for (String seed : seeds) {
            String bcqlPattern = relationConfig.get().getFullPattern(seed);
            List<QueryResults.WordSketchResult> collocates;
            collocates = executor.executeSurfacePattern(
                seed, bcqlPattern, headPos, collocatePos, minLogDice, topCollocates);
            seedToCollocates.put(seed, collocates);

            Set<String> seedCollocates = new HashSet<>();
            for (QueryResults.WordSketchResult wsr : collocates) {
                seedCollocates.add(wsr.getLemma());
            }

            if (commonCollocates == null) {
                commonCollocates = new HashSet<>(seedCollocates);
            } else {
                commonCollocates.retainAll(seedCollocates);
            }
        }

        if (commonCollocates == null) {
            commonCollocates = new HashSet<>();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("seeds", new ArrayList<>(seeds));
        response.put("seed_count", seeds.size());
        response.put("relation_type", relationType);

        Map<String, Object> paramsUsed = new HashMap<>();
        paramsUsed.put("relation", relationType);
        paramsUsed.put("top", topCollocates);
        paramsUsed.put("min_shared", minShared);
        paramsUsed.put("min_logdice", minLogDice);
        response.put("parameters", paramsUsed);

        List<Map<String, Object>> discoveredCollocs = new ArrayList<>();
        for (Map.Entry<String, List<QueryResults.WordSketchResult>> entry : seedToCollocates.entrySet()) {
            for (QueryResults.WordSketchResult wsr : entry.getValue()) {
                Map<String, Object> collocMap = new HashMap<>();
                collocMap.put("word", wsr.getLemma());
                collocMap.put("logDice", wsr.getLogDice());
                collocMap.put("frequency", wsr.getFrequency());
                discoveredCollocs.add(collocMap);
            }
        }
        response.put("seed_collocates", discoveredCollocs);
        response.put("seed_collocates_count", discoveredCollocs.size());
        response.put("common_collocates", new ArrayList<>(commonCollocates));
        response.put("common_collocates_count", commonCollocates.size());

        response.put("discovered_nouns", new ArrayList<>());
        response.put("discovered_nouns_count", 0);

        List<Map<String, Object>> edges = new ArrayList<>();
        for (Map.Entry<String, List<QueryResults.WordSketchResult>> entry : seedToCollocates.entrySet()) {
            String seed = entry.getKey();
            for (QueryResults.WordSketchResult wsr : entry.getValue()) {
                Map<String, Object> edgeMap = new HashMap<>();
                edgeMap.put("source", seed);
                edgeMap.put("target", wsr.getLemma());
                edgeMap.put("logDice", wsr.getLogDice());
                edgeMap.put("type", relationType);
                edges.add(edgeMap);
            }
        }
        response.put("edges", edges);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Handle semantic field comparison.
     * GET /api/semantic-field?nouns=theory,model,hypothesis&min_logdice=3.0
     */
    void handleSemanticField(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HttpApiUtils.parseQueryParams(query);

        String nounsParam = params.getOrDefault("nouns", "");
        if (nounsParam.isEmpty()) {
            HttpApiUtils.sendError(exchange, 400, "Missing required parameter: nouns");
            return;
        }

        Set<String> nouns = new LinkedHashSet<>(Arrays.asList(nounsParam.split(",")));
        double minLogDice = Double.parseDouble(params.getOrDefault("min_logdice", "3.0"));
        int maxPerNoun = Integer.parseInt(params.getOrDefault("max_per_noun", "50"));

        ComparisonResult result;
        result = semanticFieldExplorer.compare(nouns, minLogDice, maxPerNoun);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("nouns", new ArrayList<>(result.getNouns()));
        response.put("min_logdice", minLogDice);

        List<Map<String, Object>> adjectives = new ArrayList<>();
        for (AdjectiveProfile adj : result.getAllAdjectives()) {
            Map<String, Object> adjMap = new HashMap<>();
            adjMap.put("word", adj.adjective);
            adjMap.put("present_in", adj.presentInCount);
            adjMap.put("total_nouns", adj.totalNouns);
            adjMap.put("avg_logdice", Math.round(adj.avgLogDice * 100.0) / 100.0);
            adjMap.put("max_logdice", Math.round(adj.maxLogDice * 100.0) / 100.0);
            adjMap.put("variance", Math.round(adj.variance * 100.0) / 100.0);
            adjMap.put("commonality_score", Math.round(adj.commonalityScore * 100.0) / 100.0);
            adjMap.put("distinctiveness_score", Math.round(adj.distinctivenessScore * 100.0) / 100.0);

            String category = adj.isFullyShared() ? "fully_shared"
                : adj.isPartiallyShared() ? "partially_shared" : "specific";
            adjMap.put("category", category);

            Map<String, Double> scores = new HashMap<>();
            if (adj.nounScores != null) {
                for (Map.Entry<String, Double> entry : adj.nounScores.entrySet()) {
                    scores.put(entry.getKey(), Math.round(entry.getValue() * 100.0) / 100.0);
                }
            }
            adjMap.put("noun_scores", scores);

            if (adj.isSpecific()) {
                adjMap.put("specific_to", adj.getStrongestNoun());
            }

            adjectives.add(adjMap);
        }
        response.put("adjectives", adjectives);
        response.put("total_adjectives", result.getAllAdjectives().size());
        response.put("fully_shared", result.getFullyShared().size());
        response.put("partially_shared", result.getPartiallyShared().size());
        response.put("specific", result.getSpecific().size());

        List<Map<String, Object>> edges = new ArrayList<>();
        if (result.getEdges() != null) {
            for (SemanticFieldExplorer.Edge edge : result.getEdges()) {
                Map<String, Object> edgeMap = new HashMap<>();
                edgeMap.put("source", edge.source);
                edgeMap.put("target", edge.target);
                edgeMap.put("logDice", Math.round(edge.weight * 100.0) / 100.0);
                edges.add(edgeMap);
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

        String adjective = params.get("adjective");
        String noun = params.get("noun");
        if (adjective == null || adjective.isEmpty() || noun == null || noun.isEmpty()) {
            HttpApiUtils.sendError(exchange, 400, "Missing required parameters: adjective and noun");
            return;
        }

        int maxExamples = Integer.parseInt(params.getOrDefault("max", "10"));

        List<String> examples;
        examples = semanticFieldExplorer.fetchExamples(adjective, noun, maxExamples);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("adjective", adjective);
        response.put("noun", noun);
        response.put("examples", examples);
        response.put("count", examples.size());

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    private static String resolveRelationAlias(String relation) {
        if (relation == null) return null;
        return switch (relation) {
            case "adj_modifier", "modifier" -> "noun_modifiers";
            case "adj_predicate", "predicate" -> "noun_adj_predicates";
            case "subject_of", "subject" -> "noun_verbs";
            case "object_of", "object" -> "verb_nouns";
            default -> relation;
        };
    }
}

package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.QueryResults;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.ComparisonResult;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.AdjectiveProfile;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.ExplorationResult;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.CoreAdjective;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pl.marcinmilkowski.word_sketch.viz.RadialPlot;

/**
 * REST API server for word sketch queries using BlackLab backend.
 *
 * Endpoints:
 * - GET /health - Health check
 * - GET /api/sketch/{lemma} - Get full word sketch with all grammatical relations
 * - GET /api/sketch/{lemma}/{relation} - Get specific grammatical relation
 * - GET /api/relations - List available grammatical relations
 */
public class WordSketchApiServer {

    private static final Logger logger = LoggerFactory.getLogger(WordSketchApiServer.class);
    private final QueryExecutor executor;
    private final String indexPath;
    private final int port;
    private final GrammarConfigLoader grammarConfig;
    private com.sun.net.httpserver.HttpServer server;

    public WordSketchApiServer(QueryExecutor executor, String indexPath, int port, GrammarConfigLoader grammarConfig) {
        this.executor = executor;
        this.indexPath = indexPath;
        this.port = port;
        this.grammarConfig = grammarConfig;
    }

    public void start() {
        try {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
            
            server.createContext("/health", exchange -> {
                if (!HttpApiUtils.requireMethod(exchange, "GET")) return;
                HttpApiUtils.sendJsonResponse(exchange, Collections.singletonMap("status", "ok"));
            });

            server.createContext("/api/sketch/", exchange -> {
                if (!HttpApiUtils.requireMethod(exchange, "GET")) return;
                String path = exchange.getRequestURI().getPath();
                String[] parts = path.substring("/api/sketch/".length()).split("/");

                if (parts.length == 0 || parts[0].isEmpty()) {
                    HttpApiUtils.sendError(exchange, 400, "Lemma required");
                    return;
                }

                String lemma = parts[0];
                
                if (parts.length > 1 && "dep".equals(parts[1])) {
                    String specificDeprel = parts.length > 2 ? parts[2] : null;
                    try {
                        if (specificDeprel != null && !specificDeprel.isEmpty()) {
                            handleDependencyRelationQuery(exchange, lemma, specificDeprel);
                        } else {
                            handleFullDependencySketch(exchange, lemma);
                        }
                    } catch (IOException e) {
                        logger.error("Dependency sketch error", e);
                        HttpApiUtils.sendError(exchange, 500, "Dependency sketch failed: " + e.getMessage());
                    }
                    return;
                }
                
                String relation = parts.length > 1 ? parts[1] : null;

                try {
                    if (relation != null && !relation.isEmpty()) {
                        handleRelationQuery(exchange, lemma, relation);
                    } else {
                        handleFullSketch(exchange, lemma);
                    }
                } catch (IOException e) {
                    logger.error("Query error", e);
                    HttpApiUtils.sendError(exchange, 500, "Query failed: " + e.getMessage());
                }
            });

            // Get available relations - from grammar config filtered by SURFACE type
            server.createContext("/api/relations", exchange -> {
                if (!HttpApiUtils.requireMethod(exchange, "GET")) return;
                JSONArray relations = new JSONArray();

                // Use grammarConfig if available, filter by SURFACE type
                if (grammarConfig != null) {
                    for (var rel : grammarConfig.getRelations()) {
                        if ("SURFACE".equals(rel.relationType())) {
                            JSONObject obj = new JSONObject();
                            obj.put("id", rel.id());
                            obj.put("name", rel.name());
                            obj.put("description", rel.description());
                            obj.put("relation_type", rel.relationType());
                            obj.put("pattern", rel.pattern());
                            relations.add(obj);
                        }
                    }
                } else {
                    HttpApiUtils.sendError(exchange, 500, "Grammar configuration not loaded");
                    return;
                }

                HttpApiUtils.sendJsonResponse(exchange, Collections.singletonMap("relations", relations));
            });

            server.createContext("/api/relations/dep", exchange -> {
                if (!HttpApiUtils.requireMethod(exchange, "GET")) return;
                JSONArray relations = new JSONArray();

                if (grammarConfig != null) {
                    for (var rel : grammarConfig.getRelations()) {
                        if ("DEP".equals(rel.relationType())) {
                            JSONObject obj = new JSONObject();
                            obj.put("id", rel.id());
                            obj.put("name", rel.name());
                            obj.put("description", rel.description());
                            obj.put("relation_type", rel.relationType());
                            obj.put("pattern", rel.pattern());
                            obj.put("deprel", rel.getDeprel());
                            relations.add(obj);
                        }
                    }
                } else {
                    HttpApiUtils.sendError(exchange, 500, "Grammar configuration not loaded");
                    return;
                }

                HttpApiUtils.sendJsonResponse(exchange, Collections.singletonMap("relations", relations));
            });

            server.createContext("/api/semantic-field/explore", exchange -> {
                if (!HttpApiUtils.requireMethod(exchange, "GET")) return;
                logger.info("Received request: {}", exchange.getRequestURI());
                try {
                    handleSemanticFieldExplore(exchange);
                } catch (IOException e) {
                    logger.error("Semantic field explore error", e);
                    HttpApiUtils.sendError(exchange, 500, "Semantic field exploration failed: " + e.getMessage());
                } catch (Exception e) {
                    logger.error("Semantic field explore unexpected error", e);
                    HttpApiUtils.sendError(exchange, 500, "Unexpected error: " + e.getMessage());
                }
            });

            server.createContext("/api/semantic-field/explore-multi", exchange -> {
                if (!HttpApiUtils.requireMethod(exchange, "GET")) return;
                logger.info("Received request: {}", exchange.getRequestURI());
                try {
                    handleSemanticFieldExploreMulti(exchange);
                } catch (IOException e) {
                    logger.error("Semantic field explore-multi error", e);
                    HttpApiUtils.sendError(exchange, 500, "Multi-seed exploration failed: " + e.getMessage());
                } catch (Exception e) {
                    logger.error("Semantic field explore-multi unexpected error", e);
                    HttpApiUtils.sendError(exchange, 500, "Unexpected error: " + e.getMessage());
                }
            });

            server.createContext("/api/semantic-field", exchange -> {
                if (!HttpApiUtils.requireMethod(exchange, "GET")) return;
                try {
                    handleSemanticField(exchange);
                } catch (IOException e) {
                    logger.error("Semantic field error", e);
                    HttpApiUtils.sendError(exchange, 500, "Semantic field comparison failed: " + e.getMessage());
                }
            });

            server.createContext("/api/semantic-field/examples", exchange -> {
                if (!HttpApiUtils.requireMethod(exchange, "GET")) return;
                try {
                    handleSemanticFieldExamples(exchange);
                } catch (IOException e) {
                    logger.error("Semantic field examples error", e);
                    HttpApiUtils.sendError(exchange, 500, "Failed to fetch examples: " + e.getMessage());
                }
            });

            server.createContext("/api/concordance/examples", exchange -> {
                if (!HttpApiUtils.requireMethod(exchange, "GET")) return;
                try {
                    handleConcordanceExamples(exchange);
                } catch (IOException e) {
                    logger.error("Concordance examples error", e);
                    HttpApiUtils.sendError(exchange, 500, "Failed to fetch concordance examples: " + e.getMessage());
                }
            });

            server.createContext("/api/visual/radial", exchange -> {
                try {
                    handleVisualRadial(exchange);
                } catch (Exception e) {
                    logger.error("Error rendering radial", e);
                    HttpApiUtils.sendError(exchange, 500, "Failed to render radial: " + e.getMessage());
                }
            });

            // POST with JSON body to avoid URL encoding issues
            server.createContext("/api/bcql", exchange -> {
                try {
                    handleBcqlQueryPost(exchange);
                } catch (Exception e) {
                    logger.error("Error executing BCQL", e);
                    HttpApiUtils.sendError(exchange, 500, "Failed to execute BCQL: " + e.getMessage());
                }
            });

            server.setExecutor(null);
            server.start();
            logger.info("API server started on port {}", port);
            logger.info("Server started on http://localhost:{}", port);
            logger.info("Endpoints:");
            logger.info("  GET  /health - Health check");
            logger.info("  GET  /api/sketch/{lemma} - Get full word sketch (surface patterns)");
            logger.info("  GET  /api/sketch/{lemma}/{relation} - Get specific surface relation");
            logger.info("  GET  /api/sketch/{lemma}/dep - Get full dependency sketch");
            logger.info("  GET  /api/sketch/{lemma}/dep/{relationId} - Get specific dependency relation");
            logger.info("  GET  /api/relations - List available surface relations");
            logger.info("  GET  /api/relations/dep - List available dependency relations");
            logger.info("  GET  /api/concordance/examples - Get concordance examples for word pair");
            logger.info("  GET  /api/visual/radial - Get radial plot SVG");
            logger.info("  POST /api/bcql - Execute BCQL query");
            logger.info("");
            logger.info("Press Ctrl+C to stop.");

        } catch (IOException e) {
            logger.error("Failed to start server", e);
            throw new RuntimeException("Failed to start server", e);
        }
    }

    private void handleFullSketch(com.sun.net.httpserver.HttpExchange exchange, String lemma) throws IOException {
        JSONObject response = new JSONObject();
        response.put("lemma", lemma);
        JSONObject patterns = new JSONObject();

        // Query each relation from grammar config
        if (grammarConfig != null) {
            for (var rel : grammarConfig.getRelations()) {
                if ("SURFACE".equals(rel.relationType())) {
                    try {
                        // Substitute headword into BCQL pattern
                        String fullPattern = rel.getFullPattern(lemma);
                        List<QueryResults.WordSketchResult> results =
                            executor.executeSurfacePattern(
                                lemma, fullPattern,
                                rel.headPosition(), rel.collocatePosition(),
                                0.0, 20);

                        if (!results.isEmpty()) {
                            JSONObject patternData = new JSONObject();
                            patternData.put("name", rel.name());
                            patternData.put("cql", rel.pattern());
                            patternData.put("collocate_pos_group", rel.collocatePosGroup());
                            
                            JSONArray collocations = new JSONArray();
                            for (QueryResults.WordSketchResult result : results) {
                                JSONObject word = new JSONObject();
                                word.put("lemma", result.getLemma());
                                word.put("frequency", result.getFrequency());
                                word.put("logDice", result.getLogDice());
                                word.put("pos", result.getPos());
                                collocations.add(word);
                            }
                            patternData.put("collocations", collocations);
                            patterns.put(rel.id(), patternData);
                        }
                    } catch (Exception e) {
                        logger.debug("Relation {} failed for lemma {}", rel.id(), lemma, e);
                    }
                }
            }
        }

        response.put("patterns", patterns);
        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    private void handleRelationQuery(com.sun.net.httpserver.HttpExchange exchange, String lemma, String relation) throws IOException {
        List<QueryResults.WordSketchResult> results = new ArrayList<>();

        // Try to find the relation in grammar config
        if (grammarConfig != null) {
            var rel = grammarConfig.getRelation(relation).orElse(null);
            if (rel != null && "SURFACE".equals(rel.relationType())) {
                try {
                    // Substitute headword into BCQL pattern
                    String fullPattern = rel.getFullPattern(lemma);
                    results = executor.executeSurfacePattern(
                        lemma, fullPattern,
                        rel.headPosition(), rel.collocatePosition(),
                        0.0, 50);
                } catch (IOException e) {
                    logger.error("Query failed", e);
                    HttpApiUtils.sendError(exchange, 500, "Query failed: " + e.getMessage());
                    return;
                }
            }
        }

        JSONArray collocations = new JSONArray();
        for (QueryResults.WordSketchResult result : results) {
            JSONObject word = new JSONObject();
            word.put("lemma", result.getLemma());
            word.put("frequency", result.getFrequency());
            word.put("logDice", result.getLogDice());
            word.put("pos", result.getPos());
            collocations.add(word);
        }

        JSONObject response = new JSONObject();
        response.put("lemma", lemma);
        response.put("relation", relation);
        response.put("collocations", collocations);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Handle full dependency sketch request.
     * Returns all dependency relations for the given lemma.
     * DEP relations use surface patterns with [deprel="..."] constraints.
     */
    private void handleFullDependencySketch(com.sun.net.httpserver.HttpExchange exchange, String lemma) throws IOException {
        JSONObject response = new JSONObject();
        response.put("lemma", lemma);
        response.put("type", "dependency");
        JSONObject relations = new JSONObject();

        if (grammarConfig != null) {
            for (var rel : grammarConfig.getRelations()) {
                if ("DEP".equals(rel.relationType())) {
                    try {
                        String deprel = rel.getDeprel();
                        if (deprel == null) continue;

                        // Use the pattern from config with headword substitution
                        String fullPattern = rel.getFullPattern(lemma);
                        
                        // Execute using surface pattern method (DEP relations use surface patterns with deprel constraints)
                        List<QueryResults.WordSketchResult> results =
                            executor.executeSurfacePattern(
                                lemma, fullPattern,
                                rel.headPosition(), rel.collocatePosition(),
                                0.0, 20);

                        if (!results.isEmpty()) {
                            JSONObject relData = new JSONObject();
                            relData.put("id", rel.id());
                            relData.put("name", rel.name());
                            relData.put("description", rel.description());
                            relData.put("deprel", deprel);
                            relData.put("total_matches", results.stream().mapToInt(r -> (int)r.getFrequency()).sum());

                            JSONArray collocations = new JSONArray();
                            for (QueryResults.WordSketchResult result : results) {
                                JSONObject word = new JSONObject();
                                word.put("lemma", result.getLemma());
                                word.put("frequency", result.getFrequency());
                                word.put("logDice", result.getLogDice());
                                word.put("pos", result.getPos());
                                collocations.add(word);
                            }
                            relData.put("collocations", collocations);
                            relations.put(rel.id(), relData);
                        }
                    } catch (Exception e) {
                        logger.debug("Dependency relation {} failed for lemma {}", rel.id(), lemma, e);
                    }
                }
            }
        }

        response.put("relations", relations);
        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Handle specific dependency relation query.
     * Returns collocates for a single dependency relation.
     * DEP relations use surface patterns with [deprel="..."] constraints.
     */
    private void handleDependencyRelationQuery(com.sun.net.httpserver.HttpExchange exchange, String lemma, String relationId) throws IOException {
        List<QueryResults.WordSketchResult> results = new ArrayList<>();

        if (grammarConfig != null) {
            var rel = grammarConfig.getRelation(relationId).orElse(null);
            if (rel != null && "DEP".equals(rel.relationType())) {
                try {
                    // Use the pattern from config with headword substitution
                    String fullPattern = rel.getFullPattern(lemma);
                    
                    // Execute using surface pattern method
                    results = executor.executeSurfacePattern(
                        lemma, fullPattern,
                        rel.headPosition(), rel.collocatePosition(),
                        0.0, 50);
                } catch (IOException e) {
                    logger.error("Dependency query failed", e);
                    HttpApiUtils.sendError(exchange, 500, "Dependency query failed: " + e.getMessage());
                    return;
                }
            }
        }

        JSONArray collocations = new JSONArray();
        for (QueryResults.WordSketchResult result : results) {
            JSONObject word = new JSONObject();
            word.put("lemma", result.getLemma());
            word.put("frequency", result.getFrequency());
            word.put("logDice", result.getLogDice());
            word.put("pos", result.getPos());
            collocations.add(word);
        }

        JSONObject response = new JSONObject();
        response.put("lemma", lemma);
        response.put("relation", relationId);
        response.put("collocations", collocations);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Handle semantic field exploration (single seed).
     * GET /api/semantic-field/explore?seed=house&relation=adj_predicate&top=15&min_shared=2&min_logdice=3.0
     */
    private void handleSemanticFieldExplore(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HttpApiUtils.parseQueryParams(query);

        String seed = params.getOrDefault("seed", "");
        if (seed.isEmpty()) {
            HttpApiUtils.sendError(exchange, 400, "Missing required parameter: seed");
            return;
        }

        // Parse relation - look up from grammar config
        String relationId = normalizeRelationId(params.getOrDefault("relation", "noun_adj_predicates").toLowerCase());

        if (grammarConfig == null) {
            HttpApiUtils.sendError(exchange, 500, "Grammar configuration not loaded");
            return;
        }

        var relationConfig = grammarConfig.getRelation(relationId);
        if (relationConfig.isEmpty()) {
            HttpApiUtils.sendError(exchange, 400, "Unknown relation: " + relationId);
            return;
        }

        String relationType = relationConfig.get().relationType();
        String relationName = relationConfig.get().name();

        int topCollocates = Integer.parseInt(params.getOrDefault("top", "15"));
        int nounsPerCollocate = Integer.parseInt(params.getOrDefault("nouns_per", "30"));
        int minShared = Integer.parseInt(params.getOrDefault("min_shared", "2"));
        double minLogDice = Double.parseDouble(params.getOrDefault("min_logdice", "3.0"));

        // Run semantic field exploration using the BCQL pattern from grammar config
        SemanticFieldExplorer explorer = new SemanticFieldExplorer(this.executor);
        
        // Get the BCQL pattern with headword substituted at the head position
        String bcqlPattern = relationConfig.get().getFullPattern(seed);
        String simplePattern = relationConfig.get().collocatePosGroup().equals("adj") ? 
            "[xpos=\"JJ.*\"]" : "[xpos=\"NN.*|VB.*\"]";
        int headPos = relationConfig.get().headPosition();
        int collocatePos = relationConfig.get().collocatePosition();
        
        ExplorationResult result = explorer.exploreByPattern(
            seed, topCollocates, nounsPerCollocate, minShared, minLogDice,
            bcqlPattern, simplePattern, relationName, headPos, collocatePos);
        explorer.close();

        // Format response
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("seed", result.seed);
        response.put("relation_type", relationType);

        // Parameters used
        Map<String, Object> paramsUsed = new HashMap<>();
        paramsUsed.put("relation", relationType);
        paramsUsed.put("top", topCollocates);
        paramsUsed.put("nouns_per", nounsPerCollocate);
        paramsUsed.put("min_shared", minShared);
        paramsUsed.put("min_logdice", minLogDice);
        response.put("parameters", paramsUsed);

        // Seed collocates
        List<Map<String, Object>> seedCollocs = new ArrayList<>();
        if (result.seedAdjectives != null) {
            for (Map.Entry<String, Double> colloc : result.seedAdjectives.entrySet()) {
                Map<String, Object> collocMap = new HashMap<>();
                collocMap.put("word", colloc.getKey());
                collocMap.put("logDice", Math.round(colloc.getValue() * 100.0) / 100.0);
                seedCollocs.add(collocMap);
            }
        }
        response.put("seed_collocates", seedCollocs);
        response.put("seed_collocates_count", seedCollocs.size());

        // Discovered nouns
        List<Map<String, Object>> nouns = new ArrayList<>();
        if (result.discoveredNouns != null) {
            for (DiscoveredNoun dn : result.discoveredNouns) {
                Map<String, Object> nounMap = new HashMap<>();
                nounMap.put("word", dn.noun);
                nounMap.put("shared_count", dn.sharedCount);
                nounMap.put("similarity_score", Math.round(dn.similarityScore * 100.0) / 100.0);
                nounMap.put("avg_logdice", Math.round(dn.avgLogDice * 100.0) / 100.0);
                nounMap.put("shared_collocates", dn.getSharedAdjectiveList());
                nouns.add(nounMap);
            }
        }
        response.put("discovered_nouns", nouns);
        response.put("discovered_nouns_count", nouns.size());

        // Core collocates
        List<Map<String, Object>> coreCollocs = new ArrayList<>();
        if (result.coreAdjectives != null) {
            for (CoreAdjective ca : result.coreAdjectives) {
                Map<String, Object> collocMap = new HashMap<>();
                collocMap.put("word", ca.adjective);
                collocMap.put("shared_by_count", ca.sharedByCount);
                collocMap.put("total_nouns", ca.totalNouns);
                collocMap.put("coverage", Math.round(ca.getCoverage() * 100.0) / 100.0);
                collocMap.put("seed_logdice", Math.round(ca.seedLogDice * 100.0) / 100.0);
                coreCollocs.add(collocMap);
            }
        }
        response.put("core_collocates", coreCollocs);
        response.put("core_collocates_count", coreCollocs.size());

        // Edges for visualization
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
    private void handleSemanticFieldExploreMulti(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        logger.info("handleSemanticFieldExploreMulti called");
        String query = exchange.getRequestURI().getQuery();
        logger.info("Query string: {}", query);
        Map<String, String> params = HttpApiUtils.parseQueryParams(query);

        String seedsStr = params.getOrDefault("seeds", "");
        logger.info("Seeds parameter: {}", seedsStr);
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

        // Parse relation
        String relationId = normalizeRelationId(params.getOrDefault("relation", "noun_adj_predicates").toLowerCase());

        if (grammarConfig == null) {
            HttpApiUtils.sendError(exchange, 500, "Grammar configuration not loaded");
            return;
        }

        var relationConfig = grammarConfig.getRelation(relationId);
        if (relationConfig.isEmpty()) {
            HttpApiUtils.sendError(exchange, 400, "Unknown relation: " + relationId);
            return;
        }

        String relationType = relationConfig.get().relationType();
        String relationName = relationConfig.get().name();

        int topCollocates = Integer.parseInt(params.getOrDefault("top", "15"));
        int minShared = Integer.parseInt(params.getOrDefault("min_shared", "2"));
        double minLogDice = Double.parseDouble(params.getOrDefault("min_logdice", "2.0"));

        // Get the BCQL pattern from grammar config
        String bcqlPattern = relationConfig.get().pattern();
        String simplePattern = relationConfig.get().collocatePosGroup().equals("adj") ? 
            "[xpos=\"JJ.*\"]" : "[xpos=\"NN.*|VB.*\"]";
        int headPos = relationConfig.get().headPosition();
        int collocatePos = relationConfig.get().collocatePosition();

        // Run multi-seed exploration using the BCQL pattern from grammar config
        Map<String, List<QueryResults.WordSketchResult>> seedToCollocates = new HashMap<>();
        Set<String> commonCollocates = null;

        for (String seed : seeds) {
            List<QueryResults.WordSketchResult> collocates;
            collocates = executor.executeSurfacePattern(
                seed, bcqlPattern, headPos, collocatePos, minLogDice, topCollocates);
            seedToCollocates.put(seed, collocates);

            Set<String> seedCollocates = new HashSet<>();
            for (QueryResults.WordSketchResult wsr : collocates) {
                seedCollocates.add(wsr.getLemma());
            }

            if (commonCollocates == null) {
                commonCollocates = seedCollocates;
            } else {
                commonCollocates.retainAll(seedCollocates);
            }
        }

        if (commonCollocates == null) {
            commonCollocates = new HashSet<>();
        }

        // Format response
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

        // Discovered collocates
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

        // Edges
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
    private void handleSemanticField(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
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

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(this.executor);
        ComparisonResult result = explorer.compare(nouns, minLogDice, maxPerNoun);
        explorer.close();

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
    private void handleSemanticFieldExamples(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HttpApiUtils.parseQueryParams(query);

        String adjective = params.get("adjective");
        String noun = params.get("noun");
        if (adjective == null || adjective.isEmpty() || noun == null || noun.isEmpty()) {
            HttpApiUtils.sendError(exchange, 400, "Missing required parameters: adjective and noun");
            return;
        }

        int maxExamples = Integer.parseInt(params.getOrDefault("max", "10"));

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(this.executor);
        List<String> examples = explorer.fetchExamples(adjective, noun, maxExamples);
        explorer.close();

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("adjective", adjective);
        response.put("noun", noun);
        response.put("examples", examples);
        response.put("count", examples.size());

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Handle concordance examples.
     * GET /api/concordance/examples?word1=theory&word2=good&relation=noun_adj_predicates&limit=10
     * Uses BCQL pattern from relations.json for the specified relation.
     */
    private void handleConcordanceExamples(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = HttpApiUtils.parseQueryParams(query);

        String word1 = params.get("word1"); // headword (e.g., "theory")
        String word2 = params.get("word2"); // collocate (e.g., "good")
        // URL decode (browsers encode & as &amp; in HTML)
        if (word1 != null) word1 = word1.replace("&amp;", "&");
        if (word2 != null) word2 = word2.replace("&amp;", "&");
        String relation = params.getOrDefault("relation", "noun_adj_predicates");
        int limit = Integer.parseInt(params.getOrDefault("limit", "10"));

        if (word1 == null || word1.isEmpty() || word2 == null || word2.isEmpty()) {
            HttpApiUtils.sendError(exchange, 400, "Missing required parameters: word1 and word2");
            return;
        }

        // Get BCQL pattern from grammar config
        String bcqlQuery = null;
        if (grammarConfig != null) {
            var rel = grammarConfig.getRelation(relation);
            if (rel.isPresent()) {
                // Substitute headword to get the full BCQL pattern
                String patternWithHead = rel.get().getFullPattern(word1);
                logger.debug("After getFullPattern: {}", patternWithHead);
                logger.debug("collocatePosition = {}", rel.get().collocatePosition());
                // Now also substitute the collocate at collocate_position
                bcqlQuery = PatternSubstitution.substituteCollocate(patternWithHead, word2, rel.get().collocatePosition());
                logger.debug("After substituteCollocate: {}", bcqlQuery);
            } else {
                logger.debug("Relation '{}' not found in grammar config", relation);
            }
        } else {
            logger.debug("grammarConfig is null");
        }

        // Fallback to generic proximity query if relation not found or query is empty
        if (bcqlQuery == null || bcqlQuery.isEmpty()) {
            bcqlQuery = String.format("\"%s\" []{0,5} \"%s\"",
                word1.toLowerCase(), word2.toLowerCase());
            logger.debug("Using fallback BCQL: {}", bcqlQuery);
        }

        List<QueryResults.ConcordanceResult> results = executor.executeBcqlQuery(bcqlQuery, limit);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("word1", word1);
        response.put("word2", word2);
        response.put("relation", relation);
        response.put("bcql", bcqlQuery);
        response.put("limit_requested", limit);
        response.put("count", results.size());

        List<Map<String, Object>> examplesList = new ArrayList<>();
        for (QueryResults.ConcordanceResult r : results) {
            Map<String, Object> exMap = new HashMap<>();
            exMap.put("sentence", r.getSentence());
            exMap.put("highlighted", r.getSentence());
            exMap.put("raw", r.getRawXml() != null ? r.getRawXml() : r.getSentence());
            examplesList.add(exMap);
        }
        response.put("examples", examplesList);

        HttpApiUtils.sendJsonResponse(exchange, response);
    }

    /**
     * Render radial plot.
     * Render radial plot.
     * POST /api/visual/radial
     * Body JSON: { center: "word", width: 840, height: 520, items: [{label:"", score: 3.2}, ...] }
     * Returns: image/svg+xml
     */
    private void handleVisualRadial(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            HttpApiUtils.sendOptionsResponse(exchange, "POST");
            return;
        }
        if (!HttpApiUtils.requireMethod(exchange, "POST")) {
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            logger.debug("Radial: body = {}", body);
            JSONObject obj = JSON.parseObject(body);
            String center = obj.getString("center");
            if (center == null) center = "";
            logger.debug("Radial: center = {}", center);
            int width = obj.getIntValue("width") == 0 ? 840 : obj.getIntValue("width");
            int height = obj.getIntValue("height") == 0 ? 520 : obj.getIntValue("height");

            JSONArray itemsArr = obj.getJSONArray("items");
            List<RadialPlot.Item> items = new ArrayList<>();
            if (itemsArr != null) {
                int limit = Math.min(40, itemsArr.size());
                for (int i = 0; i < limit; i++) {
                    JSONObject it = itemsArr.getJSONObject(i);
                    String label = it.getString("label");
                    double score = it.getDoubleValue("score");
                    items.add(new RadialPlot.Item(label, score));
                }
            }
            String mode = obj.getString("mode");

            String svg = RadialPlot.renderFromItems(center, items, width, height, mode);
            byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
            HttpApiUtils.sendBinaryResponse(exchange, "image/svg+xml; charset=utf-8", bytes);
        } catch (Exception e) {
            logger.error("Error rendering radial", e);
            HttpApiUtils.sendError(exchange, 500, "Failed to render radial: " + e.getMessage());
        }
    }

    /**
     * Handle arbitrary BCQL query (POST with JSON body to avoid URL encoding issues).
     * POST /api/bcql with body: {"query": "[lemma=\"test\"]", "limit": 20}
     */
    private void handleBcqlQueryPost(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            HttpApiUtils.sendOptionsResponse(exchange, "POST");
            return;
        }
        if (!HttpApiUtils.requireMethod(exchange, "POST")) {
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JSONObject obj = JSON.parseObject(body);
            String bcqlQuery = obj.getString("query");
            int limit = obj.getIntValue("limit");
            boolean raw = obj.getBooleanValue("raw");  // Add raw output option
            if (limit <= 0) limit = 20;

            logger.debug("BCQL query: {}", bcqlQuery);

            List<QueryResults.ConcordanceResult> results = executor.executeBcqlQuery(bcqlQuery, limit);

            Map<String, Object> response = new HashMap<>();
            response.put("query", bcqlQuery);
            response.put("hits", results.size());
            response.put("limit", limit);

            List<Map<String, Object>> resultsList = new ArrayList<>();
            for (QueryResults.ConcordanceResult r : results) {
                Map<String, Object> resultMap = new HashMap<>();
                // Plain text sentence (default)
                resultMap.put("sentence", r.getSentence());
                // Raw XML always available (for toggle)
                if (r.getRawXml() != null) {
                    resultMap.put("raw", r.getRawXml());
                }
                resultMap.put("matchStart", r.getStartOffset());
                resultMap.put("matchEnd", r.getEndOffset());
                // Add grouped BCQL fields
                if (r.getCollocateLemma() != null) {
                    resultMap.put("collocateLemma", r.getCollocateLemma());
                    resultMap.put("frequency", r.getFrequency());
                    resultMap.put("logDice", r.getLogDice());
                }
                resultsList.add(resultMap);
            }
            response.put("results", resultsList);

            HttpApiUtils.sendJsonResponse(exchange, response);
        } catch (Exception e) {
            logger.error("BCQL query error", e);
            HttpApiUtils.sendError(exchange, 400, "BCQL query error: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            logger.info("API server stopped");
        }
    }

    private static String normalizeRelationId(String relation) {
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

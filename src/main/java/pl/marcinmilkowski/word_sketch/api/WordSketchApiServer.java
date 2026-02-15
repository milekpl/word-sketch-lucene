package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import pl.marcinmilkowski.word_sketch.viz.RadialPlot;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.WordSketchQueryExecutor;
import pl.marcinmilkowski.word_sketch.query.WordSketchQueryExecutor.WordSketchResult;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.ComparisonResult;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.AdjectiveProfile;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.ExplorationResult;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.CoreAdjective;
import pl.marcinmilkowski.word_sketch.query.HybridQueryExecutor;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.TermStatistics;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.*;

/**
 * REST API server for word sketch queries.
 *
 * Endpoints:
 * - GET /health - Health check
 * - GET /api/sketch/{lemma} - Get full word sketch with all grammatical relations
 * - GET /api/sketch/{lemma}/{relation} - Get specific grammatical relation
 * - POST /api/sketch/query - Execute custom CQL pattern
 * - GET /api/relations - List available grammatical relations
 * - GET /api/semantic-field/explore?seed=house - Explore semantic class from seed word
 * - GET /api/snowball?seeds=word1,word2&depth=2 - Run snowball collocation exploration
 */
public class WordSketchApiServer {

    private static final Logger logger = LoggerFactory.getLogger(WordSketchApiServer.class);
    private final QueryExecutor executor;
    private final String indexPath;
    private final int port;
    private final GrammarConfigLoader grammarConfig;
    private com.sun.net.httpserver.HttpServer server;

    /**
     * Grammatical relation definitions based on sketchgrammar.wsdef.m4
     * 
     * IMPORTANT: For PRECOMPUTED algorithm, patterns must be SINGLE-TOKEN
     * describing the collocate type only. Multi-token patterns work with
     * SAMPLE_SCAN but not with PRECOMPUTED.
     * 
     * For nouns: we find verbs (subjects/objects), adjectives (modifiers),
     *            prepositions, other nouns (compounds), etc.
     * For verbs: we find nouns (objects/subjects), prepositions, particles,
     *            adverbs (modifiers), etc.
     * For adjectives: we find nouns, adverbs (intensifiers), verbs, etc.
     */
    public WordSketchApiServer(QueryExecutor executor, String indexPath, int port) {
        this(executor, indexPath, port, null);
    }

    public WordSketchApiServer(QueryExecutor executor, String indexPath, int port, GrammarConfigLoader grammarConfig) {
        this.executor = executor;
        this.indexPath = indexPath;
        this.port = port;
        this.grammarConfig = grammarConfig;
    }

    /**
     * Start the API server.
     */
    public void start() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
        // Health check endpoint
        server.createContext("/health", wrapHandler(this::handleHealth));

        // API endpoints
        server.createContext("/api/relations", wrapHandler(this::handleRelations));
        server.createContext("/api/sketch", wrapHandler(this::handleFullSketch));
        server.createContext("/api/sketch/query", wrapHandler(this::handleQuery));
        server.createContext("/api/semantic-field/explore", wrapHandler(this::handleSemanticFieldExplore));
        server.createContext("/api/semantic-field/explore-multi", wrapHandler(this::handleSemanticFieldExploreMulti));
        server.createContext("/api/semantic-field", wrapHandler(this::handleSemanticField));
        server.createContext("/api/semantic-field/examples", wrapHandler(this::handleSemanticFieldExamples));
        server.createContext("/api/visual/radial", wrapHandler(this::handleVisualRadial));
        server.createContext("/api/concordance/examples", wrapHandler(this::handleConcordanceExamples));
        server.createContext("/api/algorithm", wrapHandler(this::handleAlgorithm));
        server.createContext("/api/diagnostics/collocation-integrity", wrapHandler(this::handleCollocationIntegrity));
        server.createContext("/api/grammar/active", wrapHandler(this::handleGrammarActive));

        // CORS preflight handler
        server.createContext("/api/sketch/options", wrapHandler(this::handleCorsOptions));

        server.setExecutor(null);
        server.start();
        logger.info("API server started on http://localhost:{}", port);
        logger.info("Endpoints:");
        logger.info("  GET  /health                 - Health check");
        logger.info("  GET  /api/relations          - List available grammatical relations");
        logger.info("  GET  /api/sketch/{{lemma}}     - Get full word sketch");
        logger.info("  POST /api/sketch/query       - Execute custom CQL pattern");
        logger.info("  GET  /api/semantic-field/explore - Explore semantic class from seed word");
        logger.info("  GET  /api/semantic-field     - Compare adjective profiles across nouns");
        logger.info("  GET  /api/semantic-field/examples - Get examples for adjective-noun pair");
        logger.info("  POST /api/visual/radial      - Render radial plot SVG (JSON body)");
        logger.info("  GET  /api/concordance/examples - Get concordance examples for word pair");
        logger.info("  GET/POST /api/algorithm      - Get/set algorithm (PRECOMPUTED recommended; SAMPLE_SCAN & SPAN_COUNT are legacy)");
        logger.info("  GET  /api/diagnostics/collocation-integrity - Integrity diagnostics");
        logger.info("  GET  /api/grammar/active         - Get active grammar configuration");
    }

    /**
     * Wrap a handler to catch all exceptions and return JSON error.
     */
    private com.sun.net.httpserver.HttpHandler wrapHandler(
            com.sun.net.httpserver.HttpHandler handler) {
        return exchange -> {
            String method = exchange.getRequestMethod();
            // Handle CORS preflight universally
            if ("OPTIONS".equalsIgnoreCase(method)) {
                addCorsHeaders(exchange);
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            try {
                // Ensure response has CORS headers for normal requests
                addCorsHeaders(exchange);
                handler.handle(exchange);
            } catch (Throwable t) {
                if (isClientConnectionIssue(t)) {
                    logger.debug("Client disconnected: {}", t.getMessage());
                    closeQuietly(exchange);
                    return;
                }

                logger.error("Unhandled exception: {}", t.getMessage());
                t.printStackTrace();
                try {
                    if (exchange.getResponseCode() != -1) {
                        logger.warn("Cannot send error response: headers already sent");
                        closeQuietly(exchange);
                        return;
                    }

                    sendError(exchange, 500, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                } catch (Exception e) {
                    if (isClientConnectionIssue(e)) {
                        logger.debug("Failed to send error response (client disconnected): {}", e.getMessage());
                    } else {
                        logger.error("Failed to send error response: {}", e.getMessage());
                    }
                } finally {
                    closeQuietly(exchange);
                }
            }
        };
    }

    private boolean isClientConnectionIssue(Throwable t) {
        Throwable current = t;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("broken pipe")
                    || lower.contains("connection reset")
                    || lower.contains("forcibly closed")
                    || lower.contains("headers already sent")
                    || lower.contains("przerwane przez oprogramowanie zainstalowane w komputerze-hoście")
                    || lower.contains("insufficient bytes written to stream")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void closeQuietly(com.sun.net.httpserver.HttpExchange exchange) {
        try {
            exchange.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * Add CORS headers to response.
     */
    private void addCorsHeaders(com.sun.net.httpserver.HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    /**
     * Handle OPTIONS preflight requests.
     */
    private void handleOptions(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        exchange.sendResponseHeaders(204, -1);
    }

    /**
     * Stop the API server.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            logger.info("API server stopped");
        }
    }

    /**
     * Handle CORS preflight (OPTIONS) requests.
     */
    private void handleCorsOptions(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.sendResponseHeaders(204, -1);
    }

    private void handleHealth(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method)) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("service", "word-sketch-lucene");
        response.put("port", port);
        response.put("executor", buildExecutorReport(false));

        sendJson(exchange, 200, response);
    }

    /**
     * Return active grammar configuration.
     */
    private void handleGrammarActive(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method)) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");

        if (grammarConfig != null) {
            response.put("config", grammarConfig.toJson());
        } else {
            response.put("config", null);
            response.put("message", "No grammar config loaded");
        }

        sendJson(exchange, 200, response);
    }

    /**
     * List available grammatical relations.
     */
    private void handleRelations(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        // Group relations by POS - grammarConfig is required
        Map<String, List<Map<String, Object>>> relationsByPos = new HashMap<>();

        if (grammarConfig == null) {
            sendError(exchange, 500, "Grammar configuration not loaded");
            return;
        }

        List<RelationDefinition> relationsToUse = grammarConfig.getRelations().stream()
            .map(RelationDefinition::fromConfig)
            .toList();

        for (RelationDefinition rel : relationsToUse) {
            relationsByPos.computeIfAbsent(rel.posGroup(), k -> new ArrayList<>())
                .add(rel.toMap());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("relations", relationsByPos);

        sendJson(exchange, 200, response);
    }

    /**
     * Get full word sketch for a lemma.
     * GET /api/sketch/{lemma}?pos=noun,verb,adj&limit=10
     */
    private void handleFullSketch(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        try {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");

            // Extract lemma from path
            String lemma = null;
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals("sketch") && i + 1 < parts.length) {
                    lemma = parts[i + 1];
                    break;
                }
            }

            if (lemma == null || lemma.isEmpty()) {
                sendError(exchange, 400, "Missing lemma parameter");
                return;
            }

            // Parse query parameters
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQueryParams(query);

            // Filter relations by POS
            String posFilter = params.getOrDefault("pos", "");
            Set<String> allowedPos;
            if (posFilter == null || posFilter.isBlank()) {
                allowedPos = inferHeadwordPosGroups(lemma);
            } else {
                allowedPos = new HashSet<>(Arrays.asList(posFilter.toLowerCase(Locale.ROOT).split(",")));
            }
            int limit = Integer.parseInt(params.getOrDefault("limit", "20"));
            double minLogDice = Double.parseDouble(params.getOrDefault("min_logdice", "0"));

            Map<String, Object> response = new HashMap<>();
            response.put("status", "ok");
            response.put("lemma", lemma);

            Map<String, Object> patterns = new HashMap<>();

            // Use grammarConfig - it is required
            if (grammarConfig == null) {
                sendError(exchange, 500, "Grammar configuration not loaded");
                return;
            }

            List<RelationDefinition> relationsToUse = grammarConfig.getRelations().stream()
                .map(RelationDefinition::fromConfig)
                .toList();

            for (RelationDefinition rel : relationsToUse) {
                if (!allowedPos.contains(rel.posGroup())) {
                    continue;
                }

                try {
                int relationLimit = limit;
                if ("noun_compounds".equals(rel.id())) {
                    relationLimit = Math.max(limit * 5, 50);
                }

                List<WordSketchQueryExecutor.WordSketchResult> results;
                if (rel.grammaticalRelationType() != null) {
                    results = executor.findGrammaticalRelation(
                        lemma,
                        rel.grammaticalRelationType(),
                        minLogDice,
                        relationLimit
                    );
                } else {
                    results = executor.findCollocations(lemma, rel.pattern(), minLogDice, relationLimit);
                }

                results = enforceStrictRelationEvidence(lemma, rel, results, limit);

                Map<String, Object> patternData = new HashMap<>();
                patternData.put("name", rel.name());
                patternData.put("cql", rel.pattern());
                patternData.put("pos_group", rel.posGroup());
                patternData.put("collocate_pos_group", rel.collocatePosGroup());
                patternData.put("total_matches", results.size());

                List<Map<String, Object>> collocations = new ArrayList<>();
                for (WordSketchQueryExecutor.WordSketchResult r : results) {
                    Map<String, Object> colloc = new HashMap<>();
                    colloc.put("lemma", r.getLemma());
                    colloc.put("pos", r.getPos());
                    colloc.put("frequency", r.getFrequency());
                    colloc.put("logDice", Math.round(r.getLogDice() * 100.0) / 100.0);
                    colloc.put("relativeFrequency", Math.round(r.getRelativeFrequency() * 1000.0) / 1000.0);
                    colloc.put("examples", r.getExamples());
                    collocations.add(colloc);
                }
                patternData.put("collocations", collocations);

                patterns.put(rel.id(), patternData);
            } catch (Exception e) {
                Map<String, Object> errorData = new HashMap<>();
                errorData.put("name", rel.name());
                errorData.put("error", e.getMessage());
                patterns.put(rel.id(), errorData);
            }
        }

        response.put("patterns", patterns);
        response.put("executor", buildExecutorReport(true));
        sendJson(exchange, 200, response);

        } catch (Exception e) {
            // Return error as JSON instead of crashing
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            error.put("type", e.getClass().getSimpleName());
            sendJson(exchange, 500, error);
            e.printStackTrace();
        }
    }

    private Set<String> inferHeadwordPosGroups(String lemma) {
        Set<String> fallback = new HashSet<>(Arrays.asList("noun", "verb", "adj"));

        if (!(executor instanceof HybridQueryExecutor hybrid)) {
            return fallback;
        }

        if (hybrid.getStatsReader() == null) {
            return fallback;
        }

        try {
            TermStatistics stats = hybrid.getStatsReader().getStatistics(lemma.toLowerCase(Locale.ROOT));
            if (stats == null || stats.posDistribution() == null || stats.posDistribution().isEmpty()) {
                return fallback;
            }

            Map<String, Long> grouped = new HashMap<>();
            for (Map.Entry<String, Long> entry : stats.posDistribution().entrySet()) {
                String group = mapTagToPosGroup(entry.getKey());
                if (group != null) {
                    grouped.merge(group, entry.getValue(), Long::sum);
                }
            }

            if (grouped.isEmpty()) {
                return fallback;
            }

            String dominant = grouped.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

            if (dominant == null) {
                return fallback;
            }

            return new HashSet<>(Collections.singletonList(dominant));
        } catch (Exception e) {
            logger.debug("Failed to infer headword POS group for '{}': {}", lemma, e.getMessage());
            return fallback;
        }
    }

    private String mapTagToPosGroup(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        String upper = tag.toUpperCase(Locale.ROOT);
        if (upper.startsWith("NN")) return "noun";
        if (upper.startsWith("VB")) return "verb";
        if (upper.startsWith("JJ")) return "adj";
        return null;
    }

    private List<WordSketchQueryExecutor.WordSketchResult> enforceStrictRelationEvidence(
            String lemma,
            RelationDefinition rel,
            List<WordSketchQueryExecutor.WordSketchResult> results,
            int limit) {
        if (results == null || results.isEmpty()) {
            return results;
        }

        if (!"noun_compounds".equals(rel.id())) {
            return results.subList(0, Math.min(limit, results.size()));
        }

        List<WordSketchQueryExecutor.WordSketchResult> filtered = new ArrayList<>();
        try {
            pl.marcinmilkowski.word_sketch.query.ConcordanceExplorer explorer =
                new pl.marcinmilkowski.word_sketch.query.ConcordanceExplorer(indexPath, grammarConfig);
            try {
                for (WordSketchQueryExecutor.WordSketchResult result : results) {
                    List<pl.marcinmilkowski.word_sketch.query.ConcordanceExplorer.ConcordanceExample> examples =
                        explorer.fetchExamples(lemma, result.getLemma(), rel.id(), result.getPos(), 1);
                    if (!examples.isEmpty()) {
                        filtered.add(result);
                    }
                    if (filtered.size() >= limit) {
                        break;
                    }
                }
            } finally {
                explorer.close();
            }
        } catch (Exception e) {
            logger.warn("Strict relation evidence check failed for relation {} and lemma {}: {}",
                rel.id(), lemma, e.getMessage());
            return results.subList(0, Math.min(limit, results.size()));
        }

        return filtered;
    }

    /**
     * Execute custom CQL pattern.
     * POST /api/sketch/query
     * {
     *   "lemma": "house",
     *   "pattern": "[tag=jj.*]~{0,3}",
     *   "min_logdice": 0,
     *   "limit": 50
     * }
     */
    private void handleQuery(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        // Read request body
        String body = readRequestBody(exchange);

        // Parse JSON request
        JSONObject request = JSON.parseObject(body);

        String lemma = request.getString("lemma");
        String cql = request.getString("pattern");
        if (cql == null) {
            cql = request.getString("cql");  // Also support 'cql' parameter
        }
        double minLogDice = request.getDoubleValue("min_logdice");
        int limit = request.getIntValue("limit");
        if (limit == 0) {
            limit = 50;  // Default limit
        }

        if (lemma == null || cql == null) {
            sendError(exchange, 400, "Missing required fields: lemma, pattern");
            return;
        }

        // Execute query
        List<WordSketchQueryExecutor.WordSketchResult> results =
            executor.findCollocations(lemma, cql, minLogDice, limit);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("lemma", lemma);
        response.put("pattern", cql);
        response.put("total_matches", results.size());

        List<Map<String, Object>> collocations = new ArrayList<>();
        for (WordSketchQueryExecutor.WordSketchResult r : results) {
            Map<String, Object> colloc = new HashMap<>();
            colloc.put("lemma", r.getLemma());
            colloc.put("pos", r.getPos());
            colloc.put("frequency", r.getFrequency());
            colloc.put("logDice", Math.round(r.getLogDice() * 100.0) / 100.0);
            colloc.put("relativeFrequency", Math.round(r.getRelativeFrequency() * 1000.0) / 1000.0);
            colloc.put("examples", r.getExamples());
            collocations.add(colloc);
        }
        response.put("collocations", collocations);
        response.put("executor", buildExecutorReport(true));

        sendJson(exchange, 200, response);
    }

    /**
     * Handle semantic field exploration (bootstrapping from a seed word).
     * Discovers related nouns by finding words that share adjective predicates with the seed.
     * 
     * GET /api/semantic-field/explore?seed=house&relation=adj_predicate&top=15&nouns_per=30&min_shared=2&min_logdice=5.0
     * 
     * Parameters:
     * - seed: The seed word to explore from (required)
     * - relation: Grammatical relation type: adj_modifier, adj_predicate, subject_of, object_of (default: adj_predicate)
     * - top: Max collocates to use for expansion (default: 15)
     * - nouns_per: Max nouns to fetch per collocate (default: 30)
     * - min_shared: Min collocates a noun must share with seed (default: 2)
     * - min_logdice: Min logDice threshold for collocations (default: 3.0)
     */
    private void handleSemanticFieldExplore(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        try {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQueryParams(query);

            // Parse parameters
            String seed = params.getOrDefault("seed", "");
            if (seed.isEmpty()) {
                sendError(exchange, 400, "Missing required parameter: seed");
                return;
            }

            // Parse relation - look up from grammar config
            String relationId = params.getOrDefault("relation", "noun_adj_predicates").toLowerCase();
            // Map legacy names to grammar config IDs
            relationId = switch (relationId) {
                case "adj_modifier", "modifier" -> "noun_modifiers";
                case "adj_predicate", "predicate" -> "noun_adj_predicates";
                case "subject_of", "subject" -> "noun_verbs";
                case "object_of", "object" -> "verb_nouns";
                default -> relationId;
            };

            if (grammarConfig == null) {
                sendError(exchange, 500, "Grammar configuration not loaded");
                return;
            }

            var relationConfig = grammarConfig.getRelation(relationId);
            if (relationConfig.isEmpty()) {
                sendError(exchange, 400, "Unknown relation: " + relationId);
                return;
            }

            String relationType = relationConfig.get().relationType();

            int topCollocates = Integer.parseInt(params.getOrDefault("top", 
                params.getOrDefault("top_adj", "15")));
            int nounsPerCollocate = Integer.parseInt(params.getOrDefault("nouns_per", 
                params.getOrDefault("nouns_per_adj", "30")));
            int minShared = Integer.parseInt(params.getOrDefault("min_shared", "2"));
            double minLogDice = Double.parseDouble(params.getOrDefault("min_logdice", "3.0"));

            // Run semantic field exploration with specified relation
            SemanticFieldExplorer explorer = new SemanticFieldExplorer(indexPath);
            QueryExecutor.RelationType legacyRelationType = toLegacyRelationType(relationType);
            ExplorationResult result = explorer.exploreByRelation(
                seed, topCollocates, nounsPerCollocate, minShared, minLogDice, legacyRelationType);
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

            // Seed collocates (adjectives, verbs, etc. depending on relation type)
            List<Map<String, Object>> seedCollocs = new ArrayList<>();
            for (Map.Entry<String, Double> colloc : result.seedAdjectives.entrySet()) {
                Map<String, Object> collocMap = new HashMap<>();
                collocMap.put("word", colloc.getKey());
                collocMap.put("logDice", Math.round(colloc.getValue() * 100.0) / 100.0);
                seedCollocs.add(collocMap);
            }
            response.put("seed_collocates", seedCollocs);
            response.put("seed_collocates_count", seedCollocs.size());
            // Keep old key for backward compatibility
            response.put("seed_adjectives", seedCollocs);
            response.put("seed_adjectives_count", seedCollocs.size());

            // Discovered nouns (semantic class)
            List<Map<String, Object>> nouns = new ArrayList<>();
            for (DiscoveredNoun dn : result.discoveredNouns) {
                Map<String, Object> nounMap = new HashMap<>();
                nounMap.put("word", dn.noun);
                nounMap.put("shared_count", dn.sharedCount);
                nounMap.put("similarity_score", Math.round(dn.similarityScore * 100.0) / 100.0);
                nounMap.put("avg_logdice", Math.round(dn.avgLogDice * 100.0) / 100.0);
                nounMap.put("shared_collocates", dn.getSharedAdjectiveList());
                // Keep old key for backward compatibility
                nounMap.put("shared_adjectives", dn.getSharedAdjectiveList());
                nouns.add(nounMap);
            }
            response.put("discovered_nouns", nouns);
            response.put("discovered_nouns_count", nouns.size());

            // Core collocates (define the semantic class)
            List<Map<String, Object>> coreCollocs = new ArrayList<>();
            for (CoreAdjective ca : result.coreAdjectives) {
                Map<String, Object> collocMap = new HashMap<>();
                collocMap.put("word", ca.adjective);
                collocMap.put("shared_by_count", ca.sharedByCount);
                collocMap.put("total_nouns", ca.totalNouns);
                collocMap.put("coverage", Math.round(ca.getCoverage() * 100.0) / 100.0);
                collocMap.put("seed_logdice", Math.round(ca.seedLogDice * 100.0) / 100.0);
                collocMap.put("avg_logdice", Math.round(ca.avgLogDice * 100.0) / 100.0);
                coreCollocs.add(collocMap);
            }
            response.put("core_collocates", coreCollocs);
            response.put("core_collocates_count", coreCollocs.size());
            // Keep old keys for backward compatibility
            response.put("core_adjectives", coreCollocs);
            response.put("core_adjectives_count", coreCollocs.size());

            // Edges for visualization
            List<Map<String, Object>> edges = new ArrayList<>();
            for (SemanticFieldExplorer.Edge edge : result.getEdges()) {
                Map<String, Object> edgeMap = new HashMap<>();
                edgeMap.put("source", edge.source);
                edgeMap.put("target", edge.target);
                edgeMap.put("weight", Math.round(edge.weight * 100.0) / 100.0);
                edgeMap.put("type", edge.type);
                edges.add(edgeMap);
            }
            response.put("edges", edges);

            response.put("executor", buildExecutorReport(true));

            sendJson(exchange, 200, response);

        } catch (NumberFormatException e) {
            sendError(exchange, 400, "Invalid numeric parameter: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Semantic field exploration failed: " + e.getMessage());
        }
    }

    /**
     * Handle multi-seed semantic field exploration.
     * Given multiple seed nouns, finds their common collocates and then other nouns that share those collocates.
     * GET /api/semantic-field/explore-multi?seeds=theory,model,hypothesis&relation=adj_predicate&top=15&min_shared=2&min_logdice=2
     */
    private void handleSemanticFieldExploreMulti(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        try {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQueryParams(query);

            // Parse parameters
            String seedsStr = params.getOrDefault("seeds", "");
            if (seedsStr.isEmpty()) {
                sendError(exchange, 400, "Missing required parameter: seeds (comma-separated)");
                return;
            }

            // Parse seeds
            String[] seedArray = seedsStr.split(",");
            Set<String> seeds = new java.util.LinkedHashSet<>();
            for (String s : seedArray) {
                String cleaned = s.trim().toLowerCase();
                if (!cleaned.isEmpty()) {
                    seeds.add(cleaned);
                }
            }

            if (seeds.size() < 2) {
                sendError(exchange, 400, "Need at least 2 seeds for multi-seed exploration");
                return;
            }

            // Parse relation - look up from grammar config
            String relationId = params.getOrDefault("relation", "noun_adj_predicates").toLowerCase();
            // Map legacy names to grammar config IDs
            relationId = switch (relationId) {
                case "adj_modifier", "modifier" -> "noun_modifiers";
                case "adj_predicate", "predicate" -> "noun_adj_predicates";
                case "subject_of", "subject" -> "noun_verbs";
                case "object_of", "object" -> "verb_nouns";
                default -> relationId;
            };

            if (grammarConfig == null) {
                sendError(exchange, 500, "Grammar configuration not loaded");
                return;
            }

            var relationConfig = grammarConfig.getRelation(relationId);
            if (relationConfig.isEmpty()) {
                sendError(exchange, 400, "Unknown relation: " + relationId);
                return;
            }

            String relationType = relationConfig.get().relationType();

            int topCollocates = Integer.parseInt(params.getOrDefault("top", 
                params.getOrDefault("top_adj", "15")));
            int nounsPerCollocate = Integer.parseInt(params.getOrDefault("nouns_per", 
                params.getOrDefault("nouns_per_adj", "30")));
            int minShared = Integer.parseInt(params.getOrDefault("min_shared", "2"));
            double minLogDice = Double.parseDouble(params.getOrDefault("min_logdice", "2.0"));

            // Run multi-seed exploration manually using SemanticFieldExplorer for each seed, then merge
            SemanticFieldExplorer explorer = new SemanticFieldExplorer(indexPath);
            
            // Step 1: For each seed, find its collocates using the specified relation
            Map<String, List<WordSketchResult>> seedToCollocates = new HashMap<>();
            Set<String> commonCollocates = null;
            
            // Convert to legacy enum for executor
            QueryExecutor.RelationType legacyRelationType = toLegacyRelationType(relationType);

            for (String seed : seeds) {
                List<WordSketchResult> collocates = executor.findGrammaticalRelation(
                    seed, legacyRelationType, minLogDice, topCollocates);
                seedToCollocates.put(seed, collocates);
                
                // Track common collocates (intersection across all seeds)
                Set<String> seedCollocates = new HashSet<>();
                for (WordSketchResult wsr : collocates) {
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
            
            explorer.close();

            // Format response similar to single-seed exploration
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ok");
            response.put("seeds", new ArrayList<>(seeds));
            response.put("seed_count", seeds.size());
            response.put("relation_type", relationType);

            // Parameters used
            Map<String, Object> paramsUsed = new HashMap<>();
            paramsUsed.put("relation", relationType);
            paramsUsed.put("top", topCollocates);
            paramsUsed.put("min_shared", minShared);
            paramsUsed.put("min_logdice", minLogDice);
            response.put("parameters", paramsUsed);

            // Discovered collocates (adjectives/verbs/nouns) from the seeds
            List<Map<String, Object>> discoveredCollocs = new ArrayList<>();
            for (Map.Entry<String, List<WordSketchResult>> entry : seedToCollocates.entrySet()) {
                for (WordSketchResult wsr : entry.getValue()) {
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

            // For now, discovered_nouns is empty (we're just finding collocates of the seeds)
            response.put("discovered_nouns", new ArrayList<>());
            response.put("discovered_nouns_count", 0);

            // Simple edges for visualization: seed -> collocate
            List<Map<String, Object>> edges = new ArrayList<>();
            for (Map.Entry<String, List<WordSketchResult>> entry : seedToCollocates.entrySet()) {
                String seed = entry.getKey();
                for (WordSketchResult wsr : entry.getValue()) {
                    Map<String, Object> edgeMap = new HashMap<>();
                    edgeMap.put("source", seed);
                    edgeMap.put("target", wsr.getLemma());
                    edgeMap.put("weight", wsr.getLogDice());
                    edgeMap.put("type", relationType);
                    edges.add(edgeMap);
                }
            }
            response.put("edges", edges);

            sendJson(exchange, 200, response);

        } catch (Exception e) {
            logger.error("Error in multi-seed exploration", e);
            sendError(exchange, 500, "Error: " + e.getMessage());
        }
    }

    /**
     * Handle semantic field comparison.
     * Compares adjective collocate profiles across related nouns, showing both shared and specific adjectives.
     * GET /api/semantic-field?nouns=theory,model,hypothesis&min_logdice=3.0&max_per_noun=50
     */
    private void handleSemanticField(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        try {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQueryParams(query);

            // Parse parameters
            String nounsParam = params.getOrDefault("nouns", "");
            if (nounsParam.isEmpty()) {
                sendError(exchange, 400, "Missing required parameter: nouns");
                return;
            }

            Set<String> nouns = new LinkedHashSet<>(Arrays.asList(nounsParam.split(",")));
            double minLogDice = Double.parseDouble(params.getOrDefault("min_logdice", "3.0"));
            int maxPerNoun = Integer.parseInt(params.getOrDefault("max_per_noun", "50"));

            // Run semantic field comparison
            SemanticFieldExplorer explorer = new SemanticFieldExplorer(indexPath);
            ComparisonResult result = explorer.compare(nouns, minLogDice, maxPerNoun);
            explorer.close();

            // Format response
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ok");
            response.put("nouns", new ArrayList<>(result.getNouns()));
            response.put("min_logdice", minLogDice);

            // Convert adjective profiles - show graded scores for each noun
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
                
                // Category: fully_shared, partially_shared, specific
                String category = adj.isFullyShared() ? "fully_shared" 
                    : adj.isPartiallyShared() ? "partially_shared" : "specific";
                adjMap.put("category", category);
                
                // Per-noun scores (graded comparison)
                Map<String, Double> scores = new HashMap<>();
                for (Map.Entry<String, Double> entry : adj.nounScores.entrySet()) {
                    scores.put(entry.getKey(), Math.round(entry.getValue() * 100.0) / 100.0);
                }
                adjMap.put("noun_scores", scores);
                
                if (adj.isSpecific()) {
                    adjMap.put("specific_to", adj.getStrongestNoun());
                }
                
                adjectives.add(adjMap);
            }
            response.put("adjectives", adjectives);

            // Summary counts
            response.put("total_adjectives", result.getAllAdjectives().size());
            response.put("fully_shared", result.getFullyShared().size());
            response.put("partially_shared", result.getPartiallyShared().size());
            response.put("specific", result.getSpecific().size());

            // Convert edges for visualization
            List<Map<String, Object>> edges = new ArrayList<>();
            for (SemanticFieldExplorer.Edge edge : result.getEdges()) {
                Map<String, Object> edgeMap = new HashMap<>();
                edgeMap.put("source", edge.source);
                edgeMap.put("target", edge.target);
                edgeMap.put("logDice", Math.round(edge.weight * 100.0) / 100.0);
                edges.add(edgeMap);
            }
            response.put("edges", edges);
            response.put("total_edges", edges.size());
            response.put("executor", buildExecutorReport(true));

            sendJson(exchange, 200, response);

        } catch (NumberFormatException e) {
            sendError(exchange, 400, "Invalid numeric parameter: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Semantic field comparison failed: " + e.getMessage());
        }
    }

    /**
     * GET /api/semantic-field/examples?adjective=good&noun=theory&max=10
     * Fetch example sentences for an adjective-noun pair.
     */
    private void handleSemanticFieldExamples(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        try {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQueryParams(query);

            String adjective = params.get("adjective");
            String noun = params.get("noun");
            if (adjective == null || adjective.isEmpty() || noun == null || noun.isEmpty()) {
                sendError(exchange, 400, "Missing required parameters: adjective and noun");
                return;
            }

            int maxExamples = Integer.parseInt(params.getOrDefault("max", "10"));

            // Create explorer and fetch examples
            SemanticFieldExplorer explorer = new SemanticFieldExplorer(indexPath);
            List<String> examples = explorer.fetchExamples(adjective, noun, maxExamples);
            explorer.close();

            // Format response
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ok");
            response.put("adjective", adjective);
            response.put("noun", noun);
            response.put("examples", examples);
            response.put("count", examples.size());

            sendJson(exchange, 200, response);

        } catch (NumberFormatException e) {
            sendError(exchange, 400, "Invalid numeric parameter: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Failed to fetch examples: " + e.getMessage());
        }
    }

    /**
     * POST /api/visual/radial
     * Body JSON: { center: "word", width: 840, height: 520, items: [{label:"", score: 3.2}, ...] }
     * Returns: image/svg+xml
     */
    private void handleVisualRadial(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JSONObject obj = JSON.parseObject(body);
            String center = obj.getString("center");
            if (center == null) center = "";
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
            exchange.getResponseHeaders().set("Content-Type", "image/svg+xml; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            logger.error("Error rendering radial", e);
            sendError(exchange, 500, "Failed to render radial: " + e.getMessage());
        }
    }

    /**
     * GET /api/concordance/examples?word1=house&word2=big&limit=10
     * Fetch concordance examples for a word pair.
     */
    private void handleConcordanceExamples(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        try {
            String query = exchange.getRequestURI().getQuery();
            Map<String, String> params = parseQueryParams(query);

            String word1 = params.get("word1");
            String word2 = params.get("word2");
            String relation = params.getOrDefault("relation", "");
            String collocatePos = params.getOrDefault("collocate_pos", "");
            int limit = Integer.parseInt(params.getOrDefault("limit", "10"));

            if (word1 == null || word1.isEmpty() || word2 == null || word2.isEmpty()) {
                sendError(exchange, 400, "Missing required parameters: word1 and word2");
                return;
            }

            // Create explorer and fetch examples using SpanQuery + DocValues
            pl.marcinmilkowski.word_sketch.query.ConcordanceExplorer explorer =
                new pl.marcinmilkowski.word_sketch.query.ConcordanceExplorer(indexPath, grammarConfig);
            
            List<pl.marcinmilkowski.word_sketch.query.ConcordanceExplorer.ConcordanceExample> examples = 
                explorer.fetchExamples(word1, word2, relation, collocatePos, limit);
            explorer.close();

            // Format response
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ok");
            response.put("word1", word1);
            response.put("word2", word2);
            response.put("relation", relation);
            response.put("collocate_pos", collocatePos);
            response.put("limit_requested", limit);
            response.put("count", examples.size());

            // Convert examples to response format
            List<Map<String, Object>> examplesList = new ArrayList<>();
            for (pl.marcinmilkowski.word_sketch.query.ConcordanceExplorer.ConcordanceExample ex : examples) {
                Map<String, Object> exMap = new HashMap<>();
                exMap.put("sentence", ex.getSentence());
                exMap.put("highlighted", ex.getHighlightedSentence());
                exMap.put("raw", ex.getRawSentence());
                exMap.put("word1_positions", ex.getPositions1());
                exMap.put("word2_positions", ex.getPositions2());
                examplesList.add(exMap);
            }

            response.put("examples", examplesList);

            sendJson(exchange, 200, response);

        } catch (NumberFormatException e) {
            sendError(exchange, 400, "Invalid numeric parameter: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            sendError(exchange, 500, "Failed to fetch concordance examples: " + e.getMessage());
        }
    }

    private Map<String, Object> buildExecutorReport(boolean includeLastQuery) {
        Map<String, Object> report = new HashMap<>();
        report.put("type", executor.getExecutorType());

        if (executor instanceof HybridQueryExecutor hybrid) {
            report.put("algorithm", hybrid.getAlgorithm().name());
            report.put("stats", hybrid.getStatsReport());
            if (includeLastQuery) {
                report.put("last_query", hybrid.getLastQueryReport());
            }
        } else {
            Map<String, Object> stats = new HashMap<>();
            stats.put("source", "none");
            stats.put("loaded", false);
            report.put("stats", stats);
        }

        return report;
    }

    /**
     * POST /api/algorithm - Get or set the algorithm for HybridQueryExecutor.
     *
     * Supported algorithms (summary):
     * - PRECOMPUTED — Use precomputed collocations (fast, recommended; requires collocations.bin).
     * - SAMPLE_SCAN — Legacy sample‑scan algorithm (deprecated; samples sentences and scans tokens).
     * - SPAN_COUNT  — Legacy span‑count algorithm: iterate candidate lemmas (from stats)
     *                 and count matches using SpanNear queries against the index. Accurate
     *                 for on‑the‑fly counting but significantly slower than PRECOMPUTED.
     *                 Kept for compatibility and debugging; deprecated and scheduled for
     *                 removal in a future major release.
     *
     * GET returns current algorithm and available options. POST body example:
     *   { "algorithm": "PRECOMPUTED" | "SAMPLE_SCAN" | "SPAN_COUNT",
     *     "min_candidate_frequency": 5 }
     */
    private void handleAlgorithm(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        String method = exchange.getRequestMethod();

        if ("OPTIONS".equalsIgnoreCase(method)) {
            handleOptions(exchange);
            return;
        }

        if ("GET".equalsIgnoreCase(method)) {
            // Return current algorithm
            if (executor instanceof HybridQueryExecutor hybrid) {
                Map<String, Object> response = new HashMap<>();
                response.put("algorithm", hybrid.getAlgorithm().name());
                // Keep the simple available list for backwards compatibility
                response.put("available", List.of("PRECOMPUTED", "SAMPLE_SCAN", "SPAN_COUNT"));

                // Provide human-readable descriptions for each algorithm (non-breaking addition)
                Map<String, String> availableInfo = Map.of(
                    "PRECOMPUTED", "Precomputed collocations (fast, requires collocations.bin)",
                    "SAMPLE_SCAN", "Legacy sample-scan algorithm (deprecated)",
                    "SPAN_COUNT", "Legacy span-count algorithm: iterate candidates and count using SpanNear (deprecated; slower but accurate)"
                );
                response.put("available_info", availableInfo);

                response.put("min_candidate_frequency", hybrid.getMinCandidateFrequency());
                sendJson(exchange, 200, response);
            } else {
                sendError(exchange, 400, "Algorithm selection requires HybridQueryExecutor");
            }
            return;
        }

        if (!"POST".equalsIgnoreCase(method)) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        if (!(executor instanceof HybridQueryExecutor hybrid)) {
            sendError(exchange, 400, "Algorithm selection requires HybridQueryExecutor");
            return;
        }

        // Parse request body
        String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        JSONObject request = JSON.parseObject(body);
        
        String algorithmName = request.getString("algorithm");
        if (algorithmName == null || algorithmName.isEmpty()) {
            sendError(exchange, 400, "Missing 'algorithm' field. Use 'PRECOMPUTED', 'SAMPLE_SCAN', or 'SPAN_COUNT'");
            return;
        }

        try {
            HybridQueryExecutor.Algorithm algo = HybridQueryExecutor.Algorithm.valueOf(algorithmName.toUpperCase());
            if (algo == HybridQueryExecutor.Algorithm.SAMPLE_SCAN || algo == HybridQueryExecutor.Algorithm.SPAN_COUNT) {
                logger.warn("Using deprecated algorithm: {}. Recommend switching to PRECOMPUTED.", algo);
            }
            hybrid.setAlgorithm(algo);

            // Optionally set min candidate frequency
            Integer minFreq = request.getInteger("min_candidate_frequency");
            if (minFreq != null) {
                hybrid.setMinCandidateFrequency(minFreq);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("status", "ok");
            response.put("algorithm", algo.name());
            response.put("min_candidate_frequency", hybrid.getMinCandidateFrequency());
            if (algo == HybridQueryExecutor.Algorithm.SAMPLE_SCAN || algo == HybridQueryExecutor.Algorithm.SPAN_COUNT) {
                response.put("warning", "This algorithm is deprecated. Use PRECOMPUTED for 100x+ speedup.");
            }
            sendJson(exchange, 200, response);

        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "Invalid algorithm: " + algorithmName + ". Use 'PRECOMPUTED', 'SAMPLE_SCAN', or 'SPAN_COUNT'");
        }
    }

    /**
     * Diagnostics endpoint: collocation integrity check.
     * GET /api/diagnostics/collocation-integrity?top=10&headwords=a,b,c
     * Returns top-N headwords with the largest number of suspicious precomputed collocates.
     */
    private void handleCollocationIntegrity(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        String method = exchange.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            handleOptions(exchange);
            return;
        }
        if (!"GET".equalsIgnoreCase(method)) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        if (!(executor instanceof HybridQueryExecutor hybrid)) {
            sendError(exchange, 400, "Diagnostics supported only for HybridQueryExecutor");
            return;
        }

        Map<String, String> params = parseQueryParams(exchange.getRequestURI().getQuery());
        int top = Integer.parseInt(params.getOrDefault("top", "10"));
        String headwordsParam = params.getOrDefault("headwords", "");

        try {
            List<Map<String, Object>> report;
            if (headwordsParam != null && !headwordsParam.isBlank()) {
                List<String> heads = Arrays.stream(headwordsParam.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
                report = hybrid.collocationsIntegrityReportFor(heads, top);
            } else {
                report = hybrid.collocationsIntegrityTopN(top);
            }

            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "ok");
            resp.put("top", top);
            resp.put("report_count", report.size());
            resp.put("report", report);
            sendJson(exchange, 200, resp);
        } catch (Exception e) {
            sendError(exchange, 500, "Diagnostics failed: " + e.getMessage());
        }
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }

        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                try {
                    params.put(
                        java.net.URLDecoder.decode(keyValue[0], "UTF-8"),
                        java.net.URLDecoder.decode(keyValue[1], "UTF-8")
                    );
                } catch (Exception e) {
                    // Skip invalid parameters
                }
            }
        }

        return params;
    }

    private String readRequestBody(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        java.io.InputStream is = exchange.getRequestBody();
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private void sendJson(com.sun.net.httpserver.HttpExchange exchange, int status, Map<String, Object> data)
            throws IOException {
        String json = JSON.toJSONString(data, com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Expires", "0");
        exchange.sendResponseHeaders(status, 0);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private void sendError(com.sun.net.httpserver.HttpExchange exchange, int status, String message)
            throws IOException {
        Map<String, Object> error = new HashMap<>();
        error.put("status", "error");
        error.put("message", message);
        error.put("code", status);

        sendJson(exchange, status, error);
    }

    /**
     * Convert grammar relation_type string to QueryExecutor.RelationType enum.
     * This is a temporary bridge until exploration is fully grammar-driven.
     */
    private QueryExecutor.RelationType toLegacyRelationType(String relationType) {
        if (relationType == null) {
            return QueryExecutor.RelationType.ADJ_PREDICATE;
        }
        return switch (relationType.toUpperCase()) {
            case "ADJ_MODIFIER" -> QueryExecutor.RelationType.ADJ_MODIFIER;
            case "ADJ_PREDICATE" -> QueryExecutor.RelationType.ADJ_PREDICATE;
            case "SUBJECT_OF" -> QueryExecutor.RelationType.SUBJECT_OF;
            case "OBJECT_OF" -> QueryExecutor.RelationType.OBJECT_OF;
            default -> QueryExecutor.RelationType.ADJ_PREDICATE;
        };
    }

    /**
     * Relation definition record.
     */
    private record RelationDefinition(String id,
                                      String name,
                                      String pattern,
                                      String posGroup,
                                      QueryExecutor.RelationType grammaticalRelationType) {
        RelationDefinition(String id, String name, String pattern, String posGroup) {
            this(id, name, pattern, posGroup, null);
        }

        /**
         * Create RelationDefinition from GrammarConfigLoader.RelationConfig.
         */
        static RelationDefinition fromConfig(GrammarConfigLoader.RelationConfig cfg) {
            QueryExecutor.RelationType relType = null;
            if (cfg.relationType() != null) {
                try {
                    relType = QueryExecutor.RelationType.valueOf(cfg.relationType());
                } catch (IllegalArgumentException ignored) {
                    // Not a special relation type
                }
            }
            return new RelationDefinition(
                cfg.id(),
                cfg.name(),
                cfg.cqlPattern(),
                cfg.headPos(),
                relType
            );
        }

        String collocatePosGroup() {
            String normalized = pattern.toLowerCase(Locale.ROOT);
            if (normalized.contains("tag=in")) return "prep";
            if (normalized.contains("tag=rp") || normalized.contains("tag=to")) return "part";
            if (normalized.contains("tag=jj")) return "adj";
            if (normalized.contains("tag=vb")) return "verb";
            if (normalized.contains("tag=nn") || normalized.contains("tag=pos")) return "noun";
            if (normalized.contains("tag=rb")) return "adv";
            return "other";
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("id", id);
            map.put("name", name);
            map.put("pattern", pattern);
            map.put("pos_group", posGroup);
            map.put("collocate_pos_group", collocatePosGroup());
            return map;
        }
    }

    /**
     * Builder for the API server.
     */
    public static class Builder {
        private QueryExecutor executor;
        private String indexPath;
        private int port = 8080;
        private GrammarConfigLoader grammarConfig;

        public Builder withExecutor(QueryExecutor executor) {
            this.executor = executor;
            return this;
        }

        public Builder withIndexPath(String indexPath) {
            this.indexPath = indexPath;
            return this;
        }

        public Builder withPort(int port) {
            this.port = port;
            return this;
        }

        public Builder withGrammarConfig(GrammarConfigLoader grammarConfig) {
            this.grammarConfig = grammarConfig;
            return this;
        }

        public WordSketchApiServer build() {
            return new WordSketchApiServer(executor, indexPath, port, grammarConfig);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

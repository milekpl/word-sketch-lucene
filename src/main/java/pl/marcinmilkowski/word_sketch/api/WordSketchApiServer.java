package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.WordSketchQueryExecutor;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.ComparisonResult;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer.AdjectiveProfile;
import pl.marcinmilkowski.word_sketch.query.HybridQueryExecutor;

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
 * - GET /api/snowball?seeds=word1,word2&depth=2 - Run snowball collocation exploration
 */
public class WordSketchApiServer {

    private final QueryExecutor executor;
    private final String indexPath;
    private final int port;
    private com.sun.net.httpserver.HttpServer server;

    /**
     * Grammatical relation definitions based on sketchgrammar.wsdef.m4
     */
    private static final List<RelationDefinition> RELATIONS = Arrays.asList(
        // Noun relations
        new RelationDefinition("noun_modifiers", "Adjectives modifying (modifiers)",
            "[tag=jj.*]~{0,3}", "noun"),
        new RelationDefinition("noun_objects", "Verbs with as object",
            "[tag=vb.*]~{0,5} [tag=nn.*]", "noun"),
        new RelationDefinition("noun_subjects", "Verbs as subject",
            "[tag=nn.*]~{-5,0} [tag=vb.*]", "noun"),
        new RelationDefinition("noun_compound", "Nouns in compound (noun+noun)",
            "[tag=nn.*]~{1,2} [tag=nn.*]", "noun"),
        new RelationDefinition("noun_adverbs", "Adverbs modifying",
            "[tag=rb.*]~{0,3}", "noun"),
        new RelationDefinition("noun_determiners", "Determiners",
            "[tag=dt]~{0,1}", "noun"),
        new RelationDefinition("noun_prepositions", "Prepositions (of, for, etc.)",
            "[word=of]~{0,3}", "noun"),

        // Verb relations
        new RelationDefinition("verb_objects", "Direct objects (what is VERBed)",
            "[tag=vb.*]~{0,5} [tag=nn.*]", "verb"),
        new RelationDefinition("verb_subjects", "Subjects (who VERBs)",
            "[tag=nn.*]~{-5,0} [tag=vb.*]", "verb"),
        new RelationDefinition("verb_particles", "Particles (verb+particle)",
            "[tag=vb.*]~{0,2} [tag=rp]", "verb"),
        new RelationDefinition("verb_infinitive", "Infinitive 'to'",
            "[tag=vb.*]~{0,3} [word=to]~{0,2}", "verb"),
        new RelationDefinition("verb_gerunds", "Gerunds (-ing)",
            "[tag=vb.*]~{0,3} [tag=vbg]", "verb"),
        new RelationDefinition("verb_passive", "Passive 'by' agent",
            "[tag=vbn]~{0,3} [word=by]~{0,2}", "verb"),

        // Adjective relations
        new RelationDefinition("adj_nouns", "Nouns modified (predicates)",
            "[tag=nn.*]~{-3,0} [tag=jj.*]", "adj"),
        new RelationDefinition("adj_verbs", "Verbs with adjective complement",
            "[tag=vb.*]~{0,5} [tag=jj.*]", "adj"),
        new RelationDefinition("adj_adverbs", "Adverbs modifying",
            "[tag=rb.*]~{0,2} [tag=jj.*]", "adj"),
        new RelationDefinition("adj_postnominal", "After noun (postnominal)",
            "[tag=nn.*]~{0,3} [tag=jj.*]", "adj"),
        new RelationDefinition("adj_intensifiers", "With 'very' or 'too'",
            "[word=very]~{0,1} [tag=jj.*]", "adj")
    );

    public WordSketchApiServer(QueryExecutor executor, String indexPath, int port) {
        this.executor = executor;
        this.indexPath = indexPath;
        this.port = port;
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
        server.createContext("/api/semantic-field", wrapHandler(this::handleSemanticField));
        server.createContext("/api/semantic-field/examples", wrapHandler(this::handleSemanticFieldExamples));
        server.createContext("/api/algorithm", wrapHandler(this::handleAlgorithm));

        // Legacy endpoints (for backward compatibility)
        server.createContext("/sketch", wrapHandler(this::handleLegacySketch));
        server.createContext("/sketch/query", wrapHandler(this::handleLegacyQuery));

        // CORS preflight handler
        server.createContext("/api/sketch/options", wrapHandler(this::handleCorsOptions));

        server.setExecutor(null);
        server.start();
        System.out.println("API server started on http://localhost:" + port);
        System.out.println("Endpoints:");
        System.out.println("  GET  /health                 - Health check");
        System.out.println("  GET  /api/relations          - List available grammatical relations");
        System.out.println("  GET  /api/sketch/{lemma}     - Get full word sketch");
        System.out.println("  POST /api/sketch/query       - Execute custom CQL pattern");
        System.out.println("  GET  /api/semantic-field     - Semantic field exploration (shared adjective predicates)");
        System.out.println("  GET  /api/semantic-field/examples - Get examples for adjective-noun pair");
        System.out.println("  GET/POST /api/algorithm      - Get/set algorithm (SAMPLE_SCAN, SPAN_COUNT, PRECOMPUTED)");
    }

    /**
     * Wrap a handler to catch all exceptions and return JSON error.
     */
    private com.sun.net.httpserver.HttpHandler wrapHandler(
            com.sun.net.httpserver.HttpHandler handler) {
        return exchange -> {
            try {
                handler.handle(exchange);
            } catch (Throwable t) {
                System.err.println("Unhandled exception: " + t.getMessage());
                t.printStackTrace();
                try {
                    Map<String, Object> error = new HashMap<>();
                    error.put("status", "error");
                    error.put("message", t.getMessage());
                    error.put("type", t.getClass().getSimpleName());
                    String json = JSON.toJSONString(error);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(500, json.getBytes("UTF-8").length);
                    exchange.getResponseBody().write(json.getBytes("UTF-8"));
                } catch (Exception e) {
                    System.err.println("Failed to send error response: " + e.getMessage());
                }
            }
        };
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
            System.out.println("API server stopped");
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
     * List available grammatical relations.
     */
    private void handleRelations(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        // Group relations by POS
        Map<String, List<Map<String, Object>>> relationsByPos = new HashMap<>();
        for (RelationDefinition rel : RELATIONS) {
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
            String posFilter = params.getOrDefault("pos", "noun,verb,adj");
            Set<String> allowedPos = new HashSet<>(Arrays.asList(posFilter.toLowerCase().split(",")));
            int limit = Integer.parseInt(params.getOrDefault("limit", "10"));
            double minLogDice = Double.parseDouble(params.getOrDefault("min_logdice", "0"));

            Map<String, Object> response = new HashMap<>();
            response.put("status", "ok");
            response.put("lemma", lemma);

            Map<String, Object> patterns = new HashMap<>();

            for (RelationDefinition rel : RELATIONS) {
                if (!allowedPos.contains(rel.posGroup())) {
                    continue;
                }

                try {
                List<WordSketchQueryExecutor.WordSketchResult> results =
                    executor.findCollocations(lemma, rel.pattern(), minLogDice, limit);

                Map<String, Object> patternData = new HashMap<>();
                patternData.put("name", rel.name());
                patternData.put("cql", rel.pattern());
                patternData.put("pos_group", rel.posGroup());
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
     * POST /api/algorithm - Set the algorithm for HybridQueryExecutor.
     * Body: { "algorithm": "SPAN_COUNT" | "SAMPLE_SCAN" }
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
                response.put("available", List.of("SAMPLE_SCAN", "SPAN_COUNT"));
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
            sendError(exchange, 400, "Missing 'algorithm' field. Use 'SAMPLE_SCAN' or 'SPAN_COUNT'");
            return;
        }

        try {
            HybridQueryExecutor.Algorithm algo = HybridQueryExecutor.Algorithm.valueOf(algorithmName.toUpperCase());
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
            sendJson(exchange, 200, response);

        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "Invalid algorithm: " + algorithmName + ". Use 'SAMPLE_SCAN' or 'SPAN_COUNT'");
        }
    }

    /**
     * Legacy sketch handler for backward compatibility.
     */
    private void handleLegacySketch(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length < 3) {
            sendError(exchange, 400, "Missing lemma parameter");
            return;
        }

        String lemma = parts[parts.length - 1];

        // Parse query parameters
        String query = exchange.getRequestURI().getQuery();
        Map<String, String> params = parseQueryParams(query);

        double minLogDice = Double.parseDouble(params.getOrDefault("min_logdice", "0"));
        int limit = Integer.parseInt(params.getOrDefault("limit", "50"));

        List<WordSketchQueryExecutor.WordSketchResult> results =
            executor.findCollocations(lemma, "[tag=nn.*]~{0,3}", minLogDice, limit);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("lemma", lemma);
        response.put("patterns", results);

        sendJson(exchange, 200, response);
    }

    /**
     * Legacy query handler for backward compatibility.
     */
    private void handleLegacyQuery(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        String body = readRequestBody(exchange);
        JSONObject request = JSON.parseObject(body);

        String lemma = request.getString("lemma");
        String cql = request.getString("cql");
        double minLogDice = request.getDoubleValue("min_logdice");
        int limit = request.getIntValue("limit");

        if (lemma == null || cql == null) {
            sendError(exchange, 400, "Missing required fields: lemma, cql");
            return;
        }

        List<WordSketchQueryExecutor.WordSketchResult> results =
            executor.findCollocations(lemma, cql, minLogDice, limit);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("lemma", lemma);
        response.put("cql", cql);
        response.put("results", results);

        sendJson(exchange, 200, response);
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
        return baos.toString("UTF-8");
    }

    private void sendJson(com.sun.net.httpserver.HttpExchange exchange, int status, Map<String, Object> data)
            throws IOException {
        String json = JSON.toJSONString(data, com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, json.getBytes("UTF-8").length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json.getBytes("UTF-8"));
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
     * Relation definition record.
     */
    private record RelationDefinition(String id, String name, String pattern, String posGroup) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("id", id);
            map.put("name", name);
            map.put("pattern", pattern);
            map.put("pos_group", posGroup);
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

        public WordSketchApiServer build() {
            return new WordSketchApiServer(executor, indexPath, port);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import pl.marcinmilkowski.word_sketch.query.SnowballCollocations;
import pl.marcinmilkowski.word_sketch.query.SnowballCollocations.SnowballResult;
import pl.marcinmilkowski.word_sketch.query.SnowballCollocations.Edge;

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
    private static final List<RelationDefinition> RELATIONS = Arrays.asList(
        // ====== NOUN relations ======
        // Adjectives modifying the noun (e.g., "big house", "red car")
        new RelationDefinition("noun_modifiers", "Modifiers (adjectives)",
            "[tag=jj.*]", "noun"),
        // Verbs appearing near the noun (subjects/objects)
        new RelationDefinition("noun_verbs", "Verbs (subject/object of)",
            "[tag=vb.*]", "noun"),
        // Other nouns (compounds like "coffee house", "house party")
        new RelationDefinition("noun_compounds", "Nouns in compound",
            "[tag=nn.*]", "noun"),
        // Prepositions (e.g., "house of", "at the house")
        new RelationDefinition("noun_prepositions", "Prepositions",
            "[tag=in]", "noun"),
        // Adverbs (rare, e.g., "almost home")
        new RelationDefinition("noun_adverbs", "Adverbs",
            "[tag=rb.*]", "noun"),
        // Possessives ("house's")
        new RelationDefinition("noun_possessives", "Possessive nouns",
            "[tag=pos]", "noun"),

        // ====== VERB relations ======
        // Nouns appearing near verbs (objects/subjects)
        new RelationDefinition("verb_nouns", "Nouns (objects/subjects)",
            "[tag=nn.*]", "verb"),
        // Particles (e.g., "give up", "break down")
        new RelationDefinition("verb_particles", "Particles",
            "[tag=rp]", "verb"),
        // Prepositions (e.g., "depend on", "look at")
        new RelationDefinition("verb_prepositions", "Prepositions",
            "[tag=in]", "verb"),
        // Adverbs modifying verbs
        new RelationDefinition("verb_adverbs", "Adverbs",
            "[tag=rb.*]", "verb"),
        // Adjectives (predicative, e.g., "become happy")
        new RelationDefinition("verb_adjectives", "Adjectives (predicative)",
            "[tag=jj.*]", "verb"),
        // Infinitive marker (e.g., "want to", "need to")
        new RelationDefinition("verb_to", "Infinitive 'to'",
            "[tag=to]", "verb"),
        // Other verbs (chains, e.g., "try to make")
        new RelationDefinition("verb_verbs", "Other verbs",
            "[tag=vb.*]", "verb"),

        // ====== ADJECTIVE relations ======
        // Nouns modified by the adjective
        new RelationDefinition("adj_nouns", "Nouns modified",
            "[tag=nn.*]", "adj"),
        // Adverbs modifying the adjective (e.g., "very big")
        new RelationDefinition("adj_adverbs", "Adverbs (intensifiers)",
            "[tag=rb.*]", "adj"),
        // Verbs (e.g., "be happy", "become sad")
        new RelationDefinition("adj_verbs", "Verbs",
            "[tag=vb.*]", "adj"),
        // Prepositions (e.g., "afraid of", "good at")  
        new RelationDefinition("adj_prepositions", "Prepositions",
            "[tag=in]", "adj"),
        // Coordinated adjectives (e.g., "big and red")
        new RelationDefinition("adj_coordinated", "Coordinated adjectives (and/or)",
            "[tag=jj.*]", "adj")
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
        server.createContext("/api/semantic-field/explore", wrapHandler(this::handleSemanticFieldExplore));
        server.createContext("/api/semantic-field/explore-multi", wrapHandler(this::handleSemanticFieldExploreMulti));
        server.createContext("/api/semantic-field", wrapHandler(this::handleSemanticField));
        server.createContext("/api/semantic-field/examples", wrapHandler(this::handleSemanticFieldExamples));
        server.createContext("/api/concordance/examples", wrapHandler(this::handleConcordanceExamples));
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
        System.out.println("  GET  /api/semantic-field/explore - Explore semantic class from seed word");
        System.out.println("  GET  /api/semantic-field     - Compare adjective profiles across nouns");
        System.out.println("  GET  /api/semantic-field/examples - Get examples for adjective-noun pair");
        System.out.println("  GET  /api/concordance/examples - Get concordance examples for word pair");
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

            // Parse relation type
            String relationStr = params.getOrDefault("relation", "adj_predicate").toLowerCase();
            QueryExecutor.RelationType relationType = switch (relationStr) {
                case "adj_modifier", "modifier" -> QueryExecutor.RelationType.ADJ_MODIFIER;
                case "adj_predicate", "predicate" -> QueryExecutor.RelationType.ADJ_PREDICATE;
                case "subject_of", "subject" -> QueryExecutor.RelationType.SUBJECT_OF;
                case "object_of", "object" -> QueryExecutor.RelationType.OBJECT_OF;
                default -> QueryExecutor.RelationType.ADJ_PREDICATE;
            };

            int topCollocates = Integer.parseInt(params.getOrDefault("top", 
                params.getOrDefault("top_adj", "15")));
            int nounsPerCollocate = Integer.parseInt(params.getOrDefault("nouns_per", 
                params.getOrDefault("nouns_per_adj", "30")));
            int minShared = Integer.parseInt(params.getOrDefault("min_shared", "2"));
            double minLogDice = Double.parseDouble(params.getOrDefault("min_logdice", "3.0"));

            // Run semantic field exploration with specified relation
            SemanticFieldExplorer explorer = new SemanticFieldExplorer(indexPath);
            ExplorationResult result = explorer.exploreByRelation(
                seed, topCollocates, nounsPerCollocate, minShared, minLogDice, relationType);
            explorer.close();

            // Format response
            Map<String, Object> response = new HashMap<>();
            response.put("status", "ok");
            response.put("seed", result.seed);
            response.put("relation_type", relationType.name());
            
            // Parameters used
            Map<String, Object> paramsUsed = new HashMap<>();
            paramsUsed.put("relation", relationType.name());
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

            // Parse relation type
            String relationStr = params.getOrDefault("relation", "adj_predicate").toLowerCase();
            QueryExecutor.RelationType relationType = switch (relationStr) {
                case "adj_modifier", "modifier" -> QueryExecutor.RelationType.ADJ_MODIFIER;
                case "adj_predicate", "predicate" -> QueryExecutor.RelationType.ADJ_PREDICATE;
                case "subject_of", "subject" -> QueryExecutor.RelationType.SUBJECT_OF;
                case "object_of", "object" -> QueryExecutor.RelationType.OBJECT_OF;
                default -> QueryExecutor.RelationType.ADJ_PREDICATE;
            };

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
            
            for (String seed : seeds) {
                List<WordSketchResult> collocates = executor.findGrammaticalRelation(
                    seed, relationType, minLogDice, topCollocates);
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
            response.put("relation_type", relationType.name());

            // Parameters used
            Map<String, Object> paramsUsed = new HashMap<>();
            paramsUsed.put("relation", relationType.name());
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
                    edgeMap.put("type", relationType.name());
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
            int limit = Integer.parseInt(params.getOrDefault("limit", "10"));

            if (word1 == null || word1.isEmpty() || word2 == null || word2.isEmpty()) {
                sendError(exchange, 400, "Missing required parameters: word1 and word2");
                return;
            }

            // Format response
            Map<String, Object> response = new HashMap<>();
            response.put("status", "not_available");
            response.put("message", "Concordance examples are not available in HYBRID index. The lemma and word fields are not stored individually - only the sentence text is available.");
            response.put("word1", word1);
            response.put("word2", word2);
            response.put("relation", relation);
            response.put("limit_requested", limit);
            response.put("count", 0);
            response.put("examples", new ArrayList<>());

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

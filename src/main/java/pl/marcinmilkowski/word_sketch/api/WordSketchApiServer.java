package pl.marcinmilkowski.word_sketch.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.SemanticFieldExplorer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;

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
    private final SketchHandlers sketchHandlers;
    private final ExplorationHandlers explorationHandlers;
    private com.sun.net.httpserver.HttpServer server;

    public WordSketchApiServer(QueryExecutor executor, String indexPath, int port, GrammarConfigLoader grammarConfig) {
        this.executor = executor;
        this.indexPath = indexPath;
        this.port = port;
        this.grammarConfig = grammarConfig;
        if (grammarConfig == null) {
            logger.warn("WordSketchApiServer initialized without grammar configuration; " +
                "relation-based endpoints (/api/sketch, /api/relations, /api/semantic-field/explore) will return 500");
        }
        SemanticFieldExplorer semanticFieldExplorer = new SemanticFieldExplorer(executor);
        this.sketchHandlers = new SketchHandlers(executor, grammarConfig);
        this.explorationHandlers = new ExplorationHandlers(executor, grammarConfig, semanticFieldExplorer);
        // TODO(595bb84c): Move HttpServer creation and route registration here so start() only calls server.start().
        //   Requires declaring IOException on this constructor, which is a breaking-API change — defer to v2.
    }

    public void start() {
        try {
            server = com.sun.net.httpserver.HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);

            registerGetHandler(server, "/health", exchange ->
                HttpApiUtils.sendJsonResponse(exchange, Collections.singletonMap("status", "ok")));

            registerGetHandler(server, "/api/sketch/", sketchHandlers::handleSketchRequest);

            registerGetHandler(server, "/api/relations", sketchHandlers::handleSurfaceRelations);

            registerGetHandler(server, "/api/relations/dep", sketchHandlers::handleDepRelations);

            registerGetHandler(server, "/api/semantic-field/explore", exchange -> {
                logger.info("Received request: {}", exchange.getRequestURI());
                try {
                    explorationHandlers.handleSemanticFieldExplore(exchange);
                } catch (IOException e) {
                    logger.error("Semantic field explore error", e);
                    HttpApiUtils.sendError(exchange, 500, "Semantic field exploration failed: " + e.getMessage());
                } catch (Exception e) {
                    logger.error("Semantic field explore unexpected error", e);
                    HttpApiUtils.sendError(exchange, 500, "Unexpected error: " + e.getMessage());
                }
            });

            registerGetHandler(server, "/api/semantic-field/explore-multi", exchange -> {
                logger.info("Received request: {}", exchange.getRequestURI());
                try {
                    explorationHandlers.handleSemanticFieldExploreMulti(exchange);
                } catch (IOException e) {
                    logger.error("Semantic field explore-multi error", e);
                    HttpApiUtils.sendError(exchange, 500, "Multi-seed exploration failed: " + e.getMessage());
                } catch (Exception e) {
                    logger.error("Semantic field explore-multi unexpected error", e);
                    HttpApiUtils.sendError(exchange, 500, "Unexpected error: " + e.getMessage());
                }
            });

            registerGetHandler(server, "/api/semantic-field", exchange -> {
                try {
                    explorationHandlers.handleSemanticField(exchange);
                } catch (IOException e) {
                    logger.error("Semantic field error", e);
                    HttpApiUtils.sendError(exchange, 500, "Semantic field comparison failed: " + e.getMessage());
                }
            });

            registerGetHandler(server, "/api/semantic-field/examples", exchange -> {
                try {
                    explorationHandlers.handleSemanticFieldExamples(exchange);
                } catch (IOException e) {
                    logger.error("Semantic field examples error", e);
                    HttpApiUtils.sendError(exchange, 500, "Failed to fetch examples: " + e.getMessage());
                }
            });

            registerGetHandler(server, "/api/concordance/examples", exchange -> {
                try {
                    sketchHandlers.handleConcordanceExamples(exchange);
                } catch (IOException e) {
                    logger.error("Concordance examples error", e);
                    HttpApiUtils.sendError(exchange, 500, "Failed to fetch concordance examples: " + e.getMessage());
                }
            });

            server.createContext("/api/visual/radial", exchange -> {
                try {
                    sketchHandlers.handleVisualRadial(exchange);
                } catch (Exception e) {
                    logger.error("Error rendering radial", e);
                    HttpApiUtils.sendError(exchange, 500, "Failed to render radial: " + e.getMessage());
                }
            });

            // POST with JSON body to avoid URL encoding issues
            server.createContext("/api/bcql", exchange -> {
                try {
                    sketchHandlers.handleBcqlQueryPost(exchange);
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

    /**
     * Registers a GET endpoint that automatically handles CORS preflight (OPTIONS) and
     * rejects any non-GET requests with 405 Method Not Allowed.
     */
    private void registerGetHandler(com.sun.net.httpserver.HttpServer httpServer, String path,
                                    com.sun.net.httpserver.HttpHandler handler) {
        httpServer.createContext(path, exchange -> {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpApiUtils.sendOptionsResponse(exchange, "GET");
                return;
            }
            if (!HttpApiUtils.requireMethod(exchange, "GET")) return;
            handler.handle(exchange);
        });
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            logger.info("API server stopped");
        }
    }
}

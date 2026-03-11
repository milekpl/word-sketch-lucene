package pl.marcinmilkowski.word_sketch.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Objects;

/**
 * REST API server for word sketch queries using BlackLab backend.
 *
 * Endpoints:
 * - GET  /health                            - Health check
 * - GET  /api/sketch/{lemma}                - Full word sketch (all grammatical relations)
 * - GET  /api/sketch/{lemma}/{relation}     - Specific grammatical relation
 * - GET  /api/sketch/{lemma}/dep            - Full dependency sketch
 * - GET  /api/sketch/{lemma}/dep/{deprel}   - Specific dependency relation
 * - GET  /api/relations                     - List surface relations
 * - GET  /api/relations/dep                 - List dependency relations
 * - GET  /api/semantic-field/explore        - Single-seed semantic field exploration
 * - GET  /api/semantic-field/explore-multi  - Multi-seed semantic field exploration
 * - GET  /api/semantic-field                - Semantic field overview
 * - GET  /api/semantic-field/examples       - Semantic field concordance examples
 * - GET  /api/concordance/examples          - Concordance examples for a word pair
 * - POST /api/visual/radial                 - Radial plot (POST-only)
 * - POST /api/bcql                          - Arbitrary BCQL query
 */
public class WordSketchApiServer {

    private static final Logger logger = LoggerFactory.getLogger(WordSketchApiServer.class);
    private final int port;
    private final GrammarConfigLoader grammarConfig;
    private final SketchHandlers sketchHandlers;
    private final ExplorationHandlers explorationHandlers;
    private com.sun.net.httpserver.HttpServer server;

    public WordSketchApiServer(QueryExecutor executor, int port, GrammarConfigLoader grammarConfig) {
        this.port = port;
        this.grammarConfig = Objects.requireNonNull(grammarConfig, "grammarConfig must not be null");
        SemanticFieldExplorer semanticFieldExplorer = new SemanticFieldExplorer(executor);
        this.sketchHandlers = new SketchHandlers(executor, grammarConfig);
        this.explorationHandlers = new ExplorationHandlers(grammarConfig, semanticFieldExplorer);
        // Two-phase init is intentional: the constructor wires dependencies without touching the
        // network, so subclasses and tests can instantiate without opening a port. start() is
        // the only method that performs I/O and can throw.
    }

    private void registerRoutes() {
        registerGetHandler(server, "/health", exchange ->
            HttpApiUtils.sendJsonResponse(exchange, Collections.singletonMap("status", "ok")));

        registerGetHandler(server, "/api/sketch/",
            wrapHandler(sketchHandlers::handleSketchRequest, "Sketch request"));

        registerGetHandler(server, "/api/relations", sketchHandlers::handleSurfaceRelations);

        registerGetHandler(server, "/api/relations/dep", sketchHandlers::handleDepRelations);

        registerGetHandler(server, "/api/semantic-field/explore", wrapHandler(exchange -> {
            logger.info("Received request: {}", exchange.getRequestURI());
            explorationHandlers.handleSemanticFieldExplore(exchange);
        }, "Semantic field explore"));

        registerGetHandler(server, "/api/semantic-field/explore-multi", wrapHandler(exchange -> {
            logger.info("Received request: {}", exchange.getRequestURI());
            explorationHandlers.handleSemanticFieldExploreMulti(exchange);
        }, "Multi-seed exploration"));

        registerGetHandler(server, "/api/semantic-field",
            wrapHandler(explorationHandlers::handleSemanticFieldComparison, "Semantic field comparison"));

        registerGetHandler(server, "/api/semantic-field/examples",
            wrapHandler(explorationHandlers::handleSemanticFieldExamples, "Semantic field examples"));

        registerGetHandler(server, "/api/concordance/examples",
            wrapHandler(sketchHandlers::handleConcordanceExamples, "Concordance examples"));

        registerPostHandler(server, "/api/visual/radial",
            wrapHandler(sketchHandlers::handleVisualRadial, "Radial plot"));

        // POST with JSON body to avoid URL encoding issues
        registerPostHandler(server, "/api/bcql",
            wrapHandler(sketchHandlers::handleBcqlQueryPost, "BCQL query"));
    }

    public void start() {
        try {
            server = com.sun.net.httpserver.HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);

            registerRoutes();

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

    /**
     * Registers a POST endpoint that automatically handles CORS preflight (OPTIONS) and
     * rejects any non-POST requests with 405 Method Not Allowed.
     * Mirrors {@link #registerGetHandler} for POST routes.
     */
    private void registerPostHandler(com.sun.net.httpserver.HttpServer httpServer, String path,
                                     com.sun.net.httpserver.HttpHandler handler) {
        httpServer.createContext(path, exchange -> {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpApiUtils.sendOptionsResponse(exchange, "POST");
                return;
            }
            if (!HttpApiUtils.requireMethod(exchange, "POST")) return;
            handler.handle(exchange);
        });
    }

    /**
     * Wraps an {@link com.sun.net.httpserver.HttpHandler} with uniform error handling.
     * Catches {@link IOException} and any other {@link Exception}, logs them, and sends a 500 response.
     */
    private com.sun.net.httpserver.HttpHandler wrapHandler(
            com.sun.net.httpserver.HttpHandler handler, String description) {
        return exchange -> {
            try {
                handler.handle(exchange);
            } catch (IOException e) {
                logger.error("{} error", description, e);
                HttpApiUtils.sendError(exchange, 500, description + " failed: " + e.getMessage());
            } catch (Exception e) {
                logger.error("{} unexpected error", description, e);
                HttpApiUtils.sendError(exchange, 500, "Unexpected error: " + e.getMessage());
            }
        };
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            logger.info("API server stopped");
        }
    }
}

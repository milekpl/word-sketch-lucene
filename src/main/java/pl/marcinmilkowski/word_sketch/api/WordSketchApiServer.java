package pl.marcinmilkowski.word_sketch.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
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
    private final GrammarConfig grammarConfig;
    private final SketchHandlers sketchHandlers;
    private final ExplorationHandlers explorationHandlers;
    private final com.sun.net.httpserver.HttpServer server;

    public WordSketchApiServer(QueryExecutor executor, int port, GrammarConfig grammarConfig) throws IOException {
        this.port = port;
        this.grammarConfig = Objects.requireNonNull(grammarConfig, "grammarConfig must not be null");
        SemanticFieldExplorer semanticFieldExplorer = new SemanticFieldExplorer(executor, grammarConfig);
        this.sketchHandlers = new SketchHandlers(executor, grammarConfig);
        this.explorationHandlers = new ExplorationHandlers(grammarConfig, semanticFieldExplorer);
        this.server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        registerRoutes();
    }

    private void registerRoutes() {
        registerGetHandler(server, "/health", exchange ->
            HttpApiUtils.sendJsonResponse(exchange, Collections.singletonMap("status", "ok")));

        registerGetHandler(server, "/api/sketch/",
            wrapHandler(sketchHandlers::handleSketchRequest, "Sketch request"));

        registerGetHandler(server, "/api/relations",
            wrapHandler(sketchHandlers::handleSurfaceRelations, "Surface relations"));

        registerGetHandler(server, "/api/relations/dep",
            wrapHandler(sketchHandlers::handleDepRelations, "Dependency relations"));

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
        server.setExecutor(null);
        server.start();
        logger.info("API server started on port {} — see class Javadoc for endpoint listing", port);
        logger.info("Press Ctrl+C to stop.");
    }

    /**
     * Registers a GET endpoint that automatically handles CORS preflight (OPTIONS) and
     * rejects any non-GET requests with 405 Method Not Allowed.
     */
    private void registerGetHandler(com.sun.net.httpserver.HttpServer httpServer, String path,
                                    com.sun.net.httpserver.HttpHandler handler) {
        registerHandler(httpServer, path, "GET", handler);
    }

    /**
     * Registers a POST endpoint that automatically handles CORS preflight (OPTIONS) and
     * rejects any non-POST requests with 405 Method Not Allowed.
     * Mirrors {@link #registerGetHandler} for POST routes.
     */
    private void registerPostHandler(com.sun.net.httpserver.HttpServer httpServer, String path,
                                     com.sun.net.httpserver.HttpHandler handler) {
        registerHandler(httpServer, path, "POST", handler);
    }

    /**
     * Shared endpoint registration: handles CORS preflight and method enforcement.
     */
    private void registerHandler(com.sun.net.httpserver.HttpServer httpServer, String path,
                                  String method, com.sun.net.httpserver.HttpHandler handler) {
        httpServer.createContext(path, exchange -> {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                HttpApiUtils.sendOptionsResponse(exchange, method);
                return;
            }
            if (!HttpApiUtils.requireMethod(exchange, method)) return;
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

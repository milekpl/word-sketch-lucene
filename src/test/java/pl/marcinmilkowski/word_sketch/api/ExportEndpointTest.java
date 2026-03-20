package pl.marcinmilkowski.word_sketch.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer;
import pl.marcinmilkowski.word_sketch.exploration.spi.ExplorationService;
import pl.marcinmilkowski.word_sketch.model.sketch.*;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.StubQueryExecutor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests that verify export (CSV and XML) for all API endpoints that support a
 * {@code ?format=} query parameter.
 *
 * <p>Each test starts a real {@link WordSketchApiServer} on an ephemeral port using a stub
 * {@link QueryExecutor} and makes an actual HTTP request. The response body and
 * {@code Content-Type} header are verified.</p>
 */
@DisplayName("Export endpoint E2E tests")
class ExportEndpointTest {

    private WordSketchApiServer server;
    private int serverPort;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private static final WordSketchResult STUB_RESULT = new WordSketchResult(
            "empirical", "JJ", 500L, 8.3, 0.005, List.of("empirical theory"));

    private static QueryExecutor richStub() {
        return new StubQueryExecutor() {
            @Override
            public List<WordSketchResult> executeSurfaceCollocations(
                    String pattern, double minLogDice, int maxResults) {
                return List.of(STUB_RESULT);
            }
            @Override
            public List<WordSketchResult> executeCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) {
                return List.of(STUB_RESULT);
            }
            @Override
            public long getTotalFrequency(String lemma) { return 50_000L; }
            @Override
            public List<CollocateResult> executeBcqlQuery(String bcqlPattern, int maxResults) {
                return List.of(new CollocateResult("Empirical theory is important", null, 1, 9, "d1", "empirical", 0, 8.3));
            }
        };
    }

    @BeforeEach
    void startServer() {
        GrammarConfig grammar = GrammarConfigHelper.requireTestConfig();
        QueryExecutor executor = richStub();
        ExplorationService explorer = new SemanticFieldExplorer(executor, grammar);
        server = new WordSketchApiServer(executor, explorer, 0, grammar);
        server.start();
        serverPort = server.getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

    // ── Sketch exports ────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/sketch/{lemma}?format=csv returns 200 with text/csv")
    void sketchCsv_returns200WithCsvContentType() throws Exception {
        HttpResponse<String> response = get("/api/sketch/theory?format=csv");
        assertEquals(200, response.statusCode());
        assertCsvContentType(response);
        assertTrue(response.body().contains("relation,lemma,collocate,pos,frequency,log_dice"),
                "CSV must contain header row");
        assertTrue(response.body().contains("empirical"), "CSV must contain stub collocate");
    }

    @Test
    @DisplayName("GET /api/sketch/{lemma}?format=xml returns 200 with application/xml")
    void sketchXml_returns200WithXmlContentType() throws Exception {
        HttpResponse<String> response = get("/api/sketch/theory?format=xml");
        assertEquals(200, response.statusCode());
        assertXmlContentType(response);
        assertTrue(response.body().startsWith("<?xml"), "XML must start with declaration");
        assertTrue(response.body().contains("<sketch"), "XML must contain sketch element");
        assertTrue(response.body().contains("empirical"), "XML must contain stub collocate");
    }

    @Test
    @DisplayName("GET /api/sketch/{lemma}?format=json returns 200 with application/json (default)")
    void sketchJson_defaultFormat_returns200WithJson() throws Exception {
        HttpResponse<String> response = get("/api/sketch/theory?format=json");
        assertEquals(200, response.statusCode());
        assertJsonContentType(response);
    }

    @Test
    @DisplayName("GET /api/sketch/{lemma}?format=csv&export_limit=1 limits collocate rows per relation")
    void sketchCsv_withLimit_limitsRowsPerRelation() throws Exception {
        // Use a single-relation endpoint so that export_limit=1 means exactly 1 data row
        GrammarConfig grammar = GrammarConfigHelper.requireTestConfig();
        String firstRelationId = grammar.relations().stream()
                .filter(r -> r.relationType().isPresent())
                .findFirst()
                .map(pl.marcinmilkowski.word_sketch.config.RelationConfig::id)
                .orElse("adj_predicate");
        HttpResponse<String> response = get(
                "/api/sketch/theory/" + firstRelationId + "?format=csv&export_limit=1");
        assertEquals(200, response.statusCode());
        long dataRows = response.body().lines()
                .filter(l -> !l.startsWith("relation,") && !l.isBlank())
                .count();
        assertTrue(dataRows <= 1, "Expected at most 1 data row with export_limit=1, got " + dataRows);
    }

    @Test
    @DisplayName("GET /api/sketch/{lemma}?format=invalid returns 400")
    void sketch_invalidFormat_returns400() throws Exception {
        HttpResponse<String> response = get("/api/sketch/theory?format=pdf");
        assertEquals(400, response.statusCode());
    }

    @Test
    @DisplayName("Content-Disposition attachment header present in CSV response")
    void sketchCsv_contentDispositionPresent() throws Exception {
        HttpResponse<String> response = get("/api/sketch/theory?format=csv");
        String cd = response.headers().firstValue("Content-Disposition").orElse("");
        assertTrue(cd.startsWith("attachment"), "CSV response must have attachment Content-Disposition");
        assertTrue(cd.contains(".csv"), "Filename hint must end with .csv");
    }

    @Test
    @DisplayName("CSV response exposes Content-Disposition via Access-Control-Expose-Headers for cross-origin JS")
    void sketchCsv_accessControlExposeHeaders_containsContentDisposition() throws Exception {
        HttpResponse<String> response = get("/api/sketch/theory?format=csv");
        String exposed = response.headers().firstValue("Access-Control-Expose-Headers").orElse("");
        assertTrue(exposed.contains("Content-Disposition"),
                "Access-Control-Expose-Headers must include Content-Disposition for cross-origin downloads");
    }

    @Test
    @DisplayName("Content-Disposition attachment header present in XML response")
    void sketchXml_contentDispositionPresent() throws Exception {
        HttpResponse<String> response = get("/api/sketch/theory?format=xml");
        String cd = response.headers().firstValue("Content-Disposition").orElse("");
        assertTrue(cd.startsWith("attachment"), "XML response must have attachment Content-Disposition");
        assertTrue(cd.contains(".xml"), "Filename hint must end with .xml");
    }

    @Test
    @DisplayName("XML response exposes Content-Disposition via Access-Control-Expose-Headers for cross-origin JS")
    void sketchXml_accessControlExposeHeaders_containsContentDisposition() throws Exception {
        HttpResponse<String> response = get("/api/sketch/theory?format=xml");
        String exposed = response.headers().firstValue("Access-Control-Expose-Headers").orElse("");
        assertTrue(exposed.contains("Content-Disposition"),
                "Access-Control-Expose-Headers must include Content-Disposition for cross-origin downloads");
    }

    // ── Concordance exports ───────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/concordance/examples?format=csv returns 200 with text/csv")
    void concordanceCsv_returns200() throws Exception {
        HttpResponse<String> response = get(
                "/api/concordance/examples?seed=theory&collocate=empirical&format=csv");
        assertEquals(200, response.statusCode());
        assertCsvContentType(response);
        assertTrue(response.body().contains("seed,collocate,relation,sentence"), "CSV must contain header");
        assertTrue(response.body().contains("theory"), "CSV must contain seed");
    }

    @Test
    @DisplayName("GET /api/concordance/examples?format=xml returns 200 with application/xml")
    void concordanceXml_returns200() throws Exception {
        HttpResponse<String> response = get(
                "/api/concordance/examples?seed=theory&collocate=empirical&format=xml");
        assertEquals(200, response.statusCode());
        assertXmlContentType(response);
        assertTrue(response.body().contains("<concordance"), "XML must contain concordance element");
    }

    // ── Semantic field explore exports ────────────────────────────────────────

    @Test
    @DisplayName("GET /api/semantic-field/explore?format=csv returns 200 with text/csv")
    void semanticExploreCsv_returns200() throws Exception {
        HttpResponse<String> response = get(
                "/api/semantic-field/explore?seed=theory&relation=adj_predicate&format=csv");
        assertEquals(200, response.statusCode());
        assertCsvContentType(response);
        assertTrue(response.body().contains("type,word,log_dice,frequency"), "CSV must contain header");
    }

    @Test
    @DisplayName("GET /api/semantic-field/explore?format=xml returns 200 with application/xml")
    void semanticExploreXml_returns200() throws Exception {
        HttpResponse<String> response = get(
                "/api/semantic-field/explore?seed=theory&relation=adj_predicate&format=xml");
        assertEquals(200, response.statusCode());
        assertXmlContentType(response);
        assertTrue(response.body().contains("<exploration"), "XML must contain exploration element");
    }

    // ── Semantic field examples exports ───────────────────────────────────────

    @Test
    @DisplayName("GET /api/semantic-field/examples?format=csv returns 200 with text/csv")
    void semanticExamplesCsv_returns200() throws Exception {
        HttpResponse<String> response = get(
                "/api/semantic-field/examples?seed=theory&collocate=empirical&relation=adj_predicate&format=csv");
        assertEquals(200, response.statusCode());
        assertCsvContentType(response);
        assertTrue(response.body().contains("seed,collocate,relation,sentence"), "CSV must contain header");
    }

    @Test
    @DisplayName("GET /api/semantic-field/examples?format=xml returns 200 with application/xml")
    void semanticExamplesXml_returns200() throws Exception {
        HttpResponse<String> response = get(
                "/api/semantic-field/examples?seed=theory&collocate=empirical&relation=adj_predicate&format=xml");
        assertEquals(200, response.statusCode());
        assertXmlContentType(response);
        assertTrue(response.body().contains("<concordance"), "XML must contain concordance element");
    }

    // ── Semantic field compare exports ────────────────────────────────────────

    @Test
    @DisplayName("GET /api/semantic-field/compare?format=csv returns 200 with text/csv")
    void semanticCompareCsv_returns200() throws Exception {
        HttpResponse<String> response = get(
                "/api/semantic-field/compare?seeds=theory,model&format=csv");
        assertEquals(200, response.statusCode());
        assertCsvContentType(response);
        assertTrue(response.body().contains("word,category"), "CSV must contain header");
    }

    @Test
    @DisplayName("GET /api/semantic-field/compare?format=xml returns 200 with application/xml")
    void semanticCompareXml_returns200() throws Exception {
        HttpResponse<String> response = get(
                "/api/semantic-field/compare?seeds=theory,model&format=xml");
        assertEquals(200, response.statusCode());
        assertXmlContentType(response);
        assertTrue(response.body().contains("<comparison"), "XML must contain comparison element");
    }

    // ── export_limit validation ────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/concordance/examples?format=csv&export_limit=invalid returns 400")
    void concordance_invalidExportLimit_returns400() throws Exception {
        HttpResponse<String> response = get(
                "/api/concordance/examples?seed=theory&collocate=empirical&format=csv&export_limit=bad");
        assertEquals(400, response.statusCode());
    }

    // ── sanitizeHeaderFilename integration ────────────────────────────────────

    @Test
    @DisplayName("Content-Disposition filename contains no CR or LF characters (response-splitting guard)")
    void sketchCsv_contentDispositionFilename_noNewlines() throws Exception {
        // Verify that even if a caller somehow passes a crafted lemma, the header is safe.
        // We test the sanitiseHeaderFilename directly via a mock exchange to avoid needing
        // the server to accept arbitrary lemmas.
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api");
        HttpApiUtils.sendCsvResponse(ex, "a,b\n1,2\n", "evil\r\nInjected-Header: x.csv");
        String cd = ex.getResponseHeaders().getFirst("Content-Disposition");
        assertNotNull(cd, "Content-Disposition must be set");
        assertFalse(cd.contains("\r"), "CR must be stripped from Content-Disposition filename");
        assertFalse(cd.contains("\n"), "LF must be stripped from Content-Disposition filename");
    }

    @Test
    @DisplayName("Content-Disposition filename contains no double-quote characters (header token guard)")
    void xmlResponse_contentDispositionFilename_noDoubleQuotes() throws Exception {
        MockExchangeFactory.MockExchange ex = new MockExchangeFactory.MockExchange(
                "http://localhost/api");
        HttpApiUtils.sendXmlResponse(ex, "<?xml version=\"1.0\"?><r/>", "fi\"le.xml");
        String cd = ex.getResponseHeaders().getFirst("Content-Disposition");
        assertNotNull(cd, "Content-Disposition must be set");
        // The outer enclosing quotes are fine; internal quotes inside the filename must be stripped.
        // Strip the surrounding `filename="..."` wrapper to inspect just the filename value:
        int start = cd.indexOf("filename=\"") + "filename=\"".length();
        int end = cd.lastIndexOf('"');
        String filenameValue = cd.substring(start, end);
        assertFalse(filenameValue.contains("\""), "double-quote must be absent from the filename value");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertCsvContentType(HttpResponse<String> response) {
        String ct = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(ct.startsWith("text/csv"), "Expected text/csv content type, got: " + ct);
    }

    private static void assertXmlContentType(HttpResponse<String> response) {
        String ct = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(ct.contains("xml"), "Expected xml content type, got: " + ct);
    }

    private static void assertJsonContentType(HttpResponse<String> response) {
        String ct = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(ct.contains("json"), "Expected json content type, got: " + ct);
    }
}

package pl.marcinmilkowski.word_sketch.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.StubQueryExecutor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CorpusQueryHandlers} request validation:
 * body size limit, invalid JSON, missing/blank query, and pattern complexity.
 */
class CorpusQueryHandlersTest {

    private static TestExchangeFactory.MockPostBodyExchange postExchange(String body) {
        return new TestExchangeFactory.MockPostBodyExchange("http://localhost/api/bcql", body == null ? "" : body);
    }

    private static CorpusQueryHandlers handlers() {
        return new CorpusQueryHandlers(null);
    }

    private static CorpusQueryHandlers handlersWithStub() {
        QueryExecutor stub = new StubQueryExecutor() {
            @Override
            public List<QueryResults.CollocateResult> executeBcqlQuery(String bcqlPattern, int maxResults) {
                return List.of(new QueryResults.CollocateResult("a theory emerged", null, 2, 8, "d1", "theory", 1, 7.0));
            }
        };
        return new CorpusQueryHandlers(stub);
    }

    @Test
    void handleCorpusQuery_validQuery_returns200() throws Exception {
        TestExchangeFactory.MockPostBodyExchange ex = postExchange("{\"query\": \"[lemma=\\\"theory\\\"]\", \"top\": 5}");
        handlersWithStub().handleCorpusQuery(ex);
        assertEquals(200, ex.statusCode);
        ObjectNode body = HttpApiUtils.MAPPER.readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertEquals("ok", body.path("status").asText());
        assertTrue(body.has("results"), "Response must contain 'results' key");
    }

    @Test
    void handleCorpusQuery_bodyTooLarge_returns413() throws Exception {
        String oversizeBody = "x".repeat(65537);
        TestExchangeFactory.MockPostBodyExchange ex = postExchange(oversizeBody);
        HttpApiUtils.wrapWithErrorHandling(handlers()::handleCorpusQuery, "test").handle(ex);
        assertEquals(413, ex.statusCode);
    }

    @Test
    void handleCorpusQuery_invalidJson_returns400() throws Exception {
        TestExchangeFactory.MockPostBodyExchange ex = postExchange("not-json");
        HttpApiUtils.wrapWithErrorHandling(handlers()::handleCorpusQuery, "test").handle(ex);
        assertEquals(400, ex.statusCode);
        ObjectNode body = HttpApiUtils.MAPPER.readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertNotNull(body.path("error").asText(), "Error field must be present");
    }

    @Test
    void handleCorpusQuery_missingQueryField_returns400() throws Exception {
        TestExchangeFactory.MockPostBodyExchange ex = postExchange("{\"top\": 10}");
        HttpApiUtils.wrapWithErrorHandling(handlers()::handleCorpusQuery, "test").handle(ex);
        assertEquals(400, ex.statusCode);
        ObjectNode body = HttpApiUtils.MAPPER.readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertTrue(body.path("error").asText().contains("query"), "Error should mention 'query'");
    }

    @Test
    void handleCorpusQuery_blankQueryField_returns400() throws Exception {
        TestExchangeFactory.MockPostBodyExchange ex = postExchange("{\"query\": \"   \"}");
        HttpApiUtils.wrapWithErrorHandling(handlers()::handleCorpusQuery, "test").handle(ex);
        assertEquals(400, ex.statusCode);
    }

    @Test
    void handleCorpusQuery_bracketDepthExceeded_returns400() throws Exception {
        // Use a simple bracket pattern with no inner quotes so JSON stays valid
        String deepPattern = "[x]".repeat(21);
        TestExchangeFactory.MockPostBodyExchange ex = postExchange("{\"query\": \"" + deepPattern + "\"}");
        HttpApiUtils.wrapWithErrorHandling(handlers()::handleCorpusQuery, "test").handle(ex);
        assertEquals(400, ex.statusCode);
        ObjectNode body = HttpApiUtils.MAPPER.readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertTrue(body.path("error").asText().contains("complex"), "Error should mention pattern complexity");
    }
}

package pl.marcinmilkowski.word_sketch.api;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link HttpApiUtils#wrapWithErrorHandling} enforces its error-classification
 * contract: IllegalArgumentException/JsonProcessingException → 400, IOException/RuntimeException → 500.
 */
class WrapHandlerTest {

    private static MockExchangeFactory.MockExchange mockExchange() {
        return new MockExchangeFactory.MockExchange("http://localhost/test");
    }

    @Test
    void wrapWithErrorHandling_illegalArgumentException_sends400() throws Exception {
        HttpHandler throwsIae = exchange -> { throw new IllegalArgumentException("bad param"); };
        HttpExchange ex = mockExchange();

        HttpApiUtils.wrapWithErrorHandling(throwsIae, "test-op").handle(ex);

        assertEquals(400, ((MockExchangeFactory.MockExchange) ex).statusCode,
                "IAE must map to HTTP 400");
        ObjectNode body = HttpApiUtils.mapper().readValue(((MockExchangeFactory.MockExchange) ex).getResponseBodyAsString(), ObjectNode.class);
        assertTrue(body.path("error").asText().contains("bad param"),
                "Error message should include original IAE message");
    }

    @Test
    void wrapWithErrorHandling_ioException_sends500() throws Exception {
        HttpHandler throwsIoe = exchange -> { throw new IOException("disk error"); };
        HttpExchange ex = mockExchange();

        HttpApiUtils.wrapWithErrorHandling(throwsIoe, "test-op").handle(ex);

        assertEquals(500, ((MockExchangeFactory.MockExchange) ex).statusCode,
                "IOException must map to HTTP 500");
    }

    @Test
    void wrapWithErrorHandling_runtimeException_sends500() throws Exception {
        HttpHandler throwsRte = exchange -> { throw new RuntimeException("unexpected"); };
        HttpExchange ex = mockExchange();

        HttpApiUtils.wrapWithErrorHandling(throwsRte, "test-op").handle(ex);

        assertEquals(500, ((MockExchangeFactory.MockExchange) ex).statusCode,
                "RuntimeException must map to HTTP 500");
        ObjectNode body = HttpApiUtils.mapper().readValue(((MockExchangeFactory.MockExchange) ex).getResponseBodyAsString(), ObjectNode.class);
        assertTrue(body.path("error").asText().contains("unexpected"),
                "Error message should include original exception message");
    }

    @Test
    void wrapWithErrorHandling_requestEntityTooLarge_sends413() throws Exception {
        HttpHandler throwsTooLarge = exchange -> { throw new RequestEntityTooLargeException("body too large"); };
        MockExchangeFactory.MockExchange ex = mockExchange();

        HttpApiUtils.wrapWithErrorHandling(throwsTooLarge, "test-op").handle(ex);

        assertEquals(413, ex.statusCode, "RequestEntityTooLargeException must map to HTTP 413");
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertTrue(body.path("error").asText().contains("body too large"),
                "Error message should include the original message");
    }

    @Test
    void wrapWithErrorHandling_jsonProcessingException_sends400() throws Exception {
        HttpHandler throwsJpe = exchange -> {
            throw new JsonParseException(null, "unexpected token at line 1");
        };
        MockExchangeFactory.MockExchange ex = mockExchange();

        HttpApiUtils.wrapWithErrorHandling(throwsJpe, "test-op").handle(ex);

        assertEquals(400, ex.statusCode,
                "JsonProcessingException (malformed client JSON) must map to HTTP 400");
        ObjectNode body = HttpApiUtils.mapper().readValue(ex.getResponseBodyAsString(), ObjectNode.class);
        assertTrue(body.path("error").asText().startsWith("Bad request"),
                "Error message should indicate a bad request");
    }

    @Test
    void wrapWithErrorHandling_noException_passesThrough() throws Exception {
        HttpHandler ok = exchange -> HttpApiUtils.sendJsonResponse(exchange, java.util.Map.of("ok", true));
        MockExchangeFactory.MockExchange ex = mockExchange();

        HttpApiUtils.wrapWithErrorHandling(ok, "test-op").handle(ex);

        assertEquals(200, ex.statusCode, "Successful handler must produce 200");
    }
}

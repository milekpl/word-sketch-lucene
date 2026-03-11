package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link HttpApiUtils#wrapWithErrorHandling} enforces its error-classification
 * contract: IllegalArgumentException → 400, IOException/RuntimeException → 500.
 */
class WrapHandlerTest {

    private static HandlersTest.MockExchange mockExchange() {
        return new HandlersTest.MockExchange("http://localhost/test");
    }

    @Test
    void wrapWithErrorHandling_illegalArgumentException_sends400() throws Exception {
        HttpHandler throwsIae = exchange -> { throw new IllegalArgumentException("bad param"); };
        HttpExchange ex = mockExchange();

        HttpApiUtils.wrapWithErrorHandling(throwsIae, "test-op").handle(ex);

        assertEquals(400, ((HandlersTest.MockExchange) ex).statusCode,
                "IAE must map to HTTP 400");
        JSONObject body = JSON.parseObject(((HandlersTest.MockExchange) ex).getResponseBodyAsString());
        assertTrue(body.getString("error").contains("bad param"),
                "Error message should include original IAE message");
    }

    @Test
    void wrapWithErrorHandling_ioException_sends500() throws Exception {
        HttpHandler throwsIoe = exchange -> { throw new IOException("disk error"); };
        HttpExchange ex = mockExchange();

        HttpApiUtils.wrapWithErrorHandling(throwsIoe, "test-op").handle(ex);

        assertEquals(500, ((HandlersTest.MockExchange) ex).statusCode,
                "IOException must map to HTTP 500");
    }

    @Test
    void wrapWithErrorHandling_runtimeException_sends500() throws Exception {
        HttpHandler throwsRte = exchange -> { throw new RuntimeException("unexpected"); };
        HttpExchange ex = mockExchange();

        HttpApiUtils.wrapWithErrorHandling(throwsRte, "test-op").handle(ex);

        assertEquals(500, ((HandlersTest.MockExchange) ex).statusCode,
                "RuntimeException must map to HTTP 500");
        JSONObject body = JSON.parseObject(((HandlersTest.MockExchange) ex).getResponseBodyAsString());
        assertTrue(body.getString("error").contains("unexpected"),
                "Error message should include original exception message");
    }

    @Test
    void wrapWithErrorHandling_requestEntityTooLarge_sends413() throws Exception {
        HttpHandler throwsTooLarge = exchange -> { throw new RequestEntityTooLargeException("body too large"); };
        HandlersTest.MockExchange ex = mockExchange();

        HttpApiUtils.wrapWithErrorHandling(throwsTooLarge, "test-op").handle(ex);

        assertEquals(413, ex.statusCode, "RequestEntityTooLargeException must map to HTTP 413");
        JSONObject body = JSON.parseObject(ex.getResponseBodyAsString());
        assertTrue(body.getString("error").contains("body too large"),
                "Error message should include the original message");
    }

    @Test
    void wrapWithErrorHandling_noException_passesThrough() throws Exception {
        HttpHandler ok = exchange -> HttpApiUtils.sendJsonResponse(exchange, java.util.Map.of("ok", true));
        HandlersTest.MockExchange ex = mockExchange();

        HttpApiUtils.wrapWithErrorHandling(ok, "test-op").handle(ex);

        assertEquals(200, ex.statusCode, "Successful handler must produce 200");
    }
}

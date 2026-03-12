package pl.marcinmilkowski.word_sketch.api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VisualizationHandlers}: mode validation, body size limit,
 * invalid JSON, and successful radial render.
 */
class VisualizationHandlersTest {

    private static MockExchangeFactory.MockPostBodyExchange postExchange(String body) {
        return new MockExchangeFactory.MockPostBodyExchange("http://localhost/api/visual/radial", body == null ? "" : body);
    }

    private static VisualizationHandlers handlers() {
        return new VisualizationHandlers();
    }

    @Test
    void handleVisualRadial_unknownMode_throwsIAE() {
        MockExchangeFactory.MockPostBodyExchange ex = postExchange("{\"center\":\"house\",\"mode\":\"unknown\",\"items\":[]}");
        assertThrows(IllegalArgumentException.class,
                () -> handlers().handleVisualRadial(ex));
    }

    @Test
    void handleVisualRadial_bodyTooLarge_throws413Exception() throws Exception {
        String oversizeBody = "x".repeat(65537);
        MockExchangeFactory.MockPostBodyExchange ex = postExchange(oversizeBody);
        HttpApiUtils.wrapWithErrorHandling(handlers()::handleVisualRadial, "test").handle(ex);
        assertEquals(413, ex.statusCode);
    }

    @Test
    void handleVisualRadial_invalidJson_throwsIAE() {
        MockExchangeFactory.MockPostBodyExchange ex = postExchange("not-valid-json{{");
        assertThrows(IllegalArgumentException.class,
                () -> handlers().handleVisualRadial(ex));
    }

    @Test
    void handleVisualRadial_validRequest_returns200WithSvg() throws Exception {
        String body = "{\"center\":\"house\",\"width\":400,\"height\":300,\"items\":[" +
                "{\"label\":\"big\",\"score\":8.5},{\"label\":\"old\",\"score\":7.2}]}";
        MockExchangeFactory.MockPostBodyExchange ex = postExchange(body);
        handlers().handleVisualRadial(ex);
        assertEquals(200, ex.statusCode);
        String response = ex.getResponseBodyAsString();
        assertTrue(response.contains("<svg"), "Response should be SVG content");
    }

    @Test
    void handleVisualRadial_responseContentTypeIsSvg() throws Exception {
        String body = "{\"center\":\"test\",\"items\":[{\"label\":\"fast\",\"score\":6.0}]}";
        MockExchangeFactory.MockPostBodyExchange ex = postExchange(body);
        handlers().handleVisualRadial(ex);
        assertEquals(200, ex.statusCode);
        String contentType = ex.getResponseHeaders().getFirst("Content-Type");
        assertNotNull(contentType, "Content-Type header must be present");
        assertTrue(contentType.startsWith("image/svg+xml"),
            "Content-Type must be image/svg+xml, got: " + contentType);
    }
}

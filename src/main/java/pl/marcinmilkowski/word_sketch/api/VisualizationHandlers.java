package pl.marcinmilkowski.word_sketch.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.viz.RadialPlot;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP handler for visualization endpoints.
 * Extracted from {@link SketchHandlers} to separate corpus-query concerns from rendering concerns.
 */
class VisualizationHandlers {

    private static final Logger logger = LoggerFactory.getLogger(VisualizationHandlers.class);

    private static final int MAX_RADIAL_ITEMS = 40;

    /** Valid render modes for the radial plot endpoint. */
    enum RenderMode {
        SIGNED;

        /**
         * Parses a raw string parameter into a {@code RenderMode}, or returns {@code null}
         * when the value is null or blank.
         *
         * @throws IllegalArgumentException if the value is non-blank but not a recognised mode
         */
        static RenderMode parse(String value) {
            if (value == null || value.isBlank()) return null;
            try {
                return valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown mode: " + value);
            }
        }
    }

    /**
     * POST /api/visual/radial
     * Body JSON: { center: "word", width: 840, height: 520, items: [{label:"", score: 3.2}, ...] }
     * Returns: image/svg+xml
     */
    void handleVisualRadial(HttpExchange exchange) throws IOException {
        String body = HttpApiUtils.readBodyWithSizeLimit(exchange, HttpApiUtils.MAX_REQUEST_BODY_BYTES);
        logger.debug("Radial: body = {}", body);
        ObjectNode obj;
        try {
            obj = HttpApiUtils.MAPPER.readValue(body, ObjectNode.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON in request body: " + e.getMessage(), e);
        }
        String center = obj.path("center").textValue();
        if (center == null) center = "";
        logger.debug("Radial: center = {}", center);
        int width = obj.path("width").asInt(840);
        int height = obj.path("height").asInt(520);

        JsonNode itemsNode = obj.get("items");
        List<RadialPlot.Item> items = new ArrayList<>();
        if (itemsNode != null && itemsNode.isArray()) {
            ArrayNode itemsArr = (ArrayNode) itemsNode;
            int limit = Math.min(MAX_RADIAL_ITEMS, itemsArr.size());
            for (int i = 0; i < limit; i++) {
                JsonNode itemNode = itemsArr.get(i);
                if (!(itemNode instanceof ObjectNode it)) {
                    throw new IllegalArgumentException(
                        "items array contains non-object element at index " + i);
                }
                String label = it.path("label").textValue();
                double score = it.path("score").asDouble();
                items.add(new RadialPlot.Item(label, score));
            }
        }
        String modeRaw = obj.path("mode").textValue();
        RenderMode renderMode = RenderMode.parse(modeRaw);

        String svg = switch (renderMode) {
            case null   -> RadialPlot.renderFromItems(center, items, width, height, null);
            case SIGNED -> RadialPlot.renderFromItems(center, items, width, height, "signed");
        };
        byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
        HttpApiUtils.sendBinaryResponse(exchange, "image/svg+xml; charset=utf-8", bytes);
    }
}

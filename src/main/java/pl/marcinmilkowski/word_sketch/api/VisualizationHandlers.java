package pl.marcinmilkowski.word_sketch.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
    private enum RenderMode {
        SIGNED;

        static boolean isValid(String value) {
            return "SIGNED".equalsIgnoreCase(value);
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
                ObjectNode it = (ObjectNode) itemsArr.get(i);
                String label = it.path("label").textValue();
                double score = it.path("score").asDouble();
                items.add(new RadialPlot.Item(label, score));
            }
        }
        String mode = obj.path("mode").textValue();
        if (mode != null && !mode.isEmpty() && !RenderMode.isValid(mode)) {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }

        String svg = RadialPlot.renderFromItems(center, items, width, height, mode);
        byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
        HttpApiUtils.sendBinaryResponse(exchange, "image/svg+xml; charset=utf-8", bytes);
    }
}

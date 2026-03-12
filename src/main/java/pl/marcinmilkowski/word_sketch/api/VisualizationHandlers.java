package pl.marcinmilkowski.word_sketch.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.api.viz.RadialPlot;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
        @Nullable
        static RenderMode parse(@Nullable String value) {
            if (value == null || value.isBlank()) return null;
            try {
                return valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown mode: " + value);
            }
        }
    }

    /** Parsed and validated request parameters for {@link #handleVisualRadial}. */
    private record RadialRequest(String center, int width, int height,
                                 List<RadialPlot.Item> items, @Nullable RenderMode renderMode) {}

    private RadialRequest parseRadialRequest(ObjectNode obj) {
        String center = obj.path("center").textValue();
        if (center == null || center.isBlank()) {
            throw new IllegalArgumentException("Missing required field: 'center'");
        }
        if (center.length() > HttpApiUtils.MAX_PARAM_LENGTH) {
            throw new IllegalArgumentException(
                "Field 'center' exceeds maximum length of " + HttpApiUtils.MAX_PARAM_LENGTH + " characters");
        }
        int width = obj.path("width").asInt(840);
        if (width < 1 || width > 5000) {
            throw new IllegalArgumentException(
                "Field 'width' must be between 1 and 5000, got: " + width);
        }
        int height = obj.path("height").asInt(520);
        if (height < 1 || height > 5000) {
            throw new IllegalArgumentException(
                "Field 'height' must be between 1 and 5000, got: " + height);
        }

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
                if (label != null && label.length() > HttpApiUtils.MAX_PARAM_LENGTH) {
                    throw new IllegalArgumentException(
                        "items[" + i + "].label exceeds maximum length of "
                        + HttpApiUtils.MAX_PARAM_LENGTH + " characters");
                }
                double score = it.path("score").asDouble();
                items.add(new RadialPlot.Item(label, score));
            }
        }

        RenderMode renderMode = RenderMode.parse(obj.path("mode").textValue());
        return new RadialRequest(center, width, height, items, renderMode);
    }

    /**
     * POST /api/visual/radial
     * Body JSON: { center: "word", width: 840, height: 520, items: [{label:"", score: 3.2}, ...] }
     * Returns: image/svg+xml
     */
    void handleVisualRadial(HttpExchange exchange) throws IOException {
        ObjectNode obj = HttpApiUtils.readJsonBody(exchange);
        RadialRequest req = parseRadialRequest(obj);

        String svg = switch (req.renderMode()) {
            case null   -> RadialPlot.renderFromItems(req.center(), req.items(), req.width(), req.height(), null);
            case SIGNED -> RadialPlot.renderFromItems(req.center(), req.items(), req.width(), req.height(), "signed");
        };
        byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
        HttpApiUtils.sendBinaryResponse(exchange, "image/svg+xml; charset=utf-8", bytes);
    }
}

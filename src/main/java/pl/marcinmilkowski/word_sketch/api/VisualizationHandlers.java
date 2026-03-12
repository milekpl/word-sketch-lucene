package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
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
    private enum RenderMode {
        SIGNED;

        static boolean isValid(String value) {
            for (RenderMode m : values()) {
                if (m.name().equalsIgnoreCase(value)) return true;
            }
            return false;
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
        JSONObject obj;
        try {
            obj = JSON.parseObject(body);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid JSON in request body: " + e.getMessage(), e);
        }
        String center = obj.getString("center");
        if (center == null) center = "";
        logger.debug("Radial: center = {}", center);
        int width = obj.getIntValue("width", 840);
        int height = obj.getIntValue("height", 520);

        JSONArray itemsArr = obj.getJSONArray("items");
        List<RadialPlot.Item> items = new ArrayList<>();
        if (itemsArr != null) {
            int limit = Math.min(MAX_RADIAL_ITEMS, itemsArr.size());
            for (int i = 0; i < limit; i++) {
                JSONObject it = itemsArr.getJSONObject(i);
                String label = it.getString("label");
                double score = it.getDoubleValue("score");
                items.add(new RadialPlot.Item(label, score));
            }
        }
        String mode = obj.getString("mode");
        if (mode != null && !mode.isEmpty() && !RenderMode.isValid(mode)) {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }

        String svg = RadialPlot.renderFromItems(center, items, width, height, mode);
        byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
        HttpApiUtils.sendBinaryResponse(exchange, "image/svg+xml; charset=utf-8", bytes);
    }
}

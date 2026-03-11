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

    private static final int MAX_REQUEST_BODY_BYTES = 65536;

    /**
     * Render radial plot.
     * POST /api/visual/radial
     * Body JSON: { center: "word", width: 840, height: 520, items: [{label:"", score: 3.2}, ...] }
     * Returns: image/svg+xml
     */
    void handleVisualRadial(HttpExchange exchange) throws IOException {
        byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
        if (bodyBytes.length > MAX_REQUEST_BODY_BYTES) {
            throw new RequestEntityTooLargeException("Request body too large");
        }
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
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
        int width = (obj.get("width") == null) ? 840 : obj.getIntValue("width");
        int height = (obj.get("height") == null) ? 520 : obj.getIntValue("height");

        JSONArray itemsArr = obj.getJSONArray("items");
        List<RadialPlot.Item> items = new ArrayList<>();
        if (itemsArr != null) {
            int limit = Math.min(40, itemsArr.size());
            for (int i = 0; i < limit; i++) {
                JSONObject it = itemsArr.getJSONObject(i);
                String label = it.getString("label");
                double score = it.getDoubleValue("score");
                items.add(new RadialPlot.Item(label, score));
            }
        }
        String mode = obj.getString("mode");

        String svg = RadialPlot.renderFromItems(center, items, width, height, mode);
        byte[] bytes = svg.getBytes(StandardCharsets.UTF_8);
        HttpApiUtils.sendBinaryResponse(exchange, "image/svg+xml; charset=utf-8", bytes);
    }
}

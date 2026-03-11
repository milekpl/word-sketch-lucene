package pl.marcinmilkowski.word_sketch.exploration;

import java.util.HashMap;
import java.util.Map;

/**
 * A weighted, typed edge between two nodes in a semantic-field exploration graph.
 *
 * <p>{@code source} and {@code target} are lemma strings; {@code weight} is the
 * logDice association score (0–14); {@code type} categorises the relationship
 * (see {@link RelationEdgeType}).</p>
 */
public record Edge(String source, String target, double weight, RelationEdgeType type) {

    /**
     * Serialises this edge to a plain map suitable for JSON output.
     * The {@code log_dice} field is rounded to two decimal places.
     *
     * @return mutable map with keys {@code source}, {@code target}, {@code log_dice}, {@code type}
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("source", source);
        m.put("target", target);
        m.put("log_dice", Math.round(weight * 100.0) / 100.0);
        m.put("type", type.label());
        return m;
    }
}

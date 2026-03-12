package pl.marcinmilkowski.word_sketch.viz;

/**
 * A weighted, typed edge between two nodes in a semantic-field exploration graph.
 *
 * <p>{@code source} and {@code target} are lemma strings; {@code weight} is the
 * logDice association score (0–14); {@code type} categorises the relationship
 * (see {@link RelationEdgeType}).</p>
 */
public record Edge(String source, String target, double weight, RelationEdgeType type) {
}

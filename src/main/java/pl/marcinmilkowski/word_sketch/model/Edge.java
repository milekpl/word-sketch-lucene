package pl.marcinmilkowski.word_sketch.model;

/**
 * Edge for graph visualization.
 */
public record Edge(String source, String target, double weight, RelationEdgeType type) {
}

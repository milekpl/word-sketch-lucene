package pl.marcinmilkowski.word_sketch.model;

/**
 * Edge for graph visualization.
 */
public class Edge {
    public final String source;   // adjective
    public final String target;   // noun
    public final double weight;   // logDice score
    public final String type;

    public Edge(String source, String target, double weight, String type) {
        this.source = source;
        this.target = target;
        this.weight = weight;
        this.type = type;
    }
}

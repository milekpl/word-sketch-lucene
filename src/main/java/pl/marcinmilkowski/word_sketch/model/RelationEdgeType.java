package pl.marcinmilkowski.word_sketch.model;

/**
 * Type of an {@link Edge} in a semantic-field exploration graph.
 *
 * <p>The {@link #label()} value is the stable serialization key used in JSON responses
 * (e.g. {@code "seed_adj"}). Callers should prefer {@link #label()} or {@link #toString()}
 * over {@link #name()} so that API clients are not coupled to Java enum naming conventions.
 */
public enum RelationEdgeType {

    /** Adjective collocate of a seed noun. */
    SEED_ADJ("seed_adj"),

    /** Adjective collocate of a discovered noun (second-order). */
    DISCOVERED_ADJ("discovered_adj"),

    /** Adjective modifier of a noun (from comparison/radial mode). */
    MODIFIER("modifier");

    private final String label;

    RelationEdgeType(String label) {
        this.label = label;
    }

    /** Stable string label used in JSON serialization. */
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}

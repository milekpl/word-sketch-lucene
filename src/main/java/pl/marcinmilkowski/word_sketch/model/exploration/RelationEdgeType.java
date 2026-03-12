package pl.marcinmilkowski.word_sketch.model.exploration;

/**
 * Type of an {@link Edge} in a semantic-field exploration graph.
 *
 * <p>The {@link #label()} value is the stable serialization key used in JSON responses
 * (e.g. {@code "seed_adj"}). Callers should prefer {@link #label()} or {@link #toString()}
 * over {@link #name()} so that API clients are not coupled to Java enum naming conventions.
 */
public enum RelationEdgeType {

    /** Collocate of a seed noun. */
    SEED_COLLOCATE("seed_adj"),

    /** Collocate of a discovered noun (second-order). */
    DISCOVERED_COLLOCATE("discovered_adj"),

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

    /**
     * Returns the stable label, identical to {@link #label()}.
     * Overriding {@code Object.toString()} ensures that logging frameworks, template engines,
     * and string concatenation emit the API-safe label (e.g. {@code "seed_adj"}) rather than
     * the Java enum constant name (e.g. {@code "SEED_COLLOCATE"}).
     */
    @Override
    public String toString() {
        return label;
    }
}

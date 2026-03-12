package pl.marcinmilkowski.word_sketch.model;

/**
 * Relation type enum used by {@code SketchHandlers} and {@code RelationConfig#relationType()}
 * to select the query execution strategy.
 */
public enum RelationType {
    /** Surface token-sequence query: BCQL span pattern over surface form and POS tags. */
    SURFACE,
    /** Dependency-annotation query: patterns that match syntactic dependency annotations. */
    DEP
}

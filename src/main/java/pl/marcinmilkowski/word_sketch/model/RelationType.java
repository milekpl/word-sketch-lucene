package pl.marcinmilkowski.word_sketch.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Relation type enum used by {@code SketchHandlers} and {@code RelationConfig#relationType()}
 * to select the query execution strategy.
 */
public enum RelationType {
    /** Surface token-sequence query: BCQL span pattern over surface form and POS tags. */
    SURFACE,
    /** Dependency-annotation query: patterns that match syntactic dependency annotations. */
    DEP;

    /** Stable lowercase label used as the JSON wire format. */
    @JsonValue
    public String label() {
        return name().toLowerCase();
    }
}

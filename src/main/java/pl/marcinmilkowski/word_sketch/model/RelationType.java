package pl.marcinmilkowski.word_sketch.model;

/**
 * Dual-axis relation type enum serving two separate concerns.
 *
 * <p><strong>Sketch dispatch axis</strong> ({@link #SURFACE}, {@link #DEP}): used by
 * {@code SketchHandlers} and {@code RelationConfig#relationType()} to select the
 * query execution strategy — surface-token span matching vs. dependency-annotation lookup.</p>
 *
 * <p><strong>Semantic role axis</strong> ({@link #ADJ_PREDICATE}, {@link #ADJ_MODIFIER},
 * {@link #SUBJECT_OF}, {@link #OBJECT_OF}): legacy labels from an earlier exploration
 * subsystem design where relation types carried semantic meaning. They are no longer set
 * in any grammar config, but are retained as valid enum constants because some tests
 * and {@link RelationConfig} instances reference them. Priority-hint usages in the
 * exploration layer were removed — only the fallback (POS-group matching) fires in practice.</p>
 */
public enum RelationType {
    /** Surface token-sequence query: BCQL span pattern over surface form and POS tags. */
    SURFACE,
    /** Dependency-annotation query: patterns that match syntactic dependency annotations. */
    DEP,
    /** Legacy semantic role: adjective as predicate (e.g. "X is ADJ"). Not set in grammar config. */
    ADJ_PREDICATE,
    /** Legacy semantic role: adjective as modifier (e.g. "ADJ X"). Not set in grammar config. */
    ADJ_MODIFIER,
    /** Legacy semantic role: noun as subject of verb. Not set in grammar config. */
    SUBJECT_OF,
    /** Legacy semantic role: noun as object of verb. Not set in grammar config. */
    OBJECT_OF
}

package pl.marcinmilkowski.word_sketch.query;

/**
 * Constants for POS group identifiers used across the query layer, taggers, and grammar config.
 *
 * <p>These four values are the canonical string representations that flow through
 * {@link Token#getPosGroup()}, {@link PosGroupPredicate}, the grammar-config loader,
 * and the API server.  Centralising them here prevents silent mismatches caused by
 * scattered raw-string literals.
 */
public final class PosGroup {

    private PosGroup() {}

    public static final String NOUN = "noun";
    public static final String VERB = "verb";
    public static final String ADJ  = "adj";
    public static final String ADV  = "adv";
}

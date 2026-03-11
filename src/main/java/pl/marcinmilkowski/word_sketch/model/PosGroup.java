package pl.marcinmilkowski.word_sketch.model;

/**
 * POS group categories used across the query layer, taggers, and grammar config.
 *
 * <p>The string values (e.g. {@code "noun"}) are the canonical representations that
 * flow through the grammar-config loader and the API server.
 */
public enum PosGroup {
    NOUN("noun"), VERB("verb"), ADJ("adj"), ADV("adv"), OTHER("other");

    private final String value;

    PosGroup(String value) {
        this.value = value;
    }

    public String label() {
        return value;
    }

    public static PosGroup fromString(String s) {
        if (s == null) return OTHER;
        for (PosGroup g : values()) {
            if (g.value.equals(s)) return g;
        }
        return OTHER;
    }
}

package pl.marcinmilkowski.word_sketch.model;

/**
 * Adjective sharing category across a set of seed nouns in a semantic-field comparison.
 *
 * <p>Used by the response assembler in the api layer for type-safe serialization
 * of the {@code category} field in API responses.</p>
 */
public enum SharingCategory {

    /** Adjective appears in all seed nouns' collocate profiles. */
    FULLY_SHARED("fully_shared"),

    /** Adjective appears in at least 2 — but not all — seed nouns' collocate profiles. */
    PARTIALLY_SHARED("partially_shared"),

    /** Adjective appears in exactly one seed noun's collocate profile. */
    SPECIFIC("specific");

    private final String label;

    SharingCategory(String label) {
        this.label = label;
    }

    /** Stable API-safe label used in JSON serialization (e.g. {@code "fully_shared"}). */
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}

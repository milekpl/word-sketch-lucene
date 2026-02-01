package pl.marcinmilkowski.word_sketch.indexer.hybrid;

/**
 * A single collocation: a word that cooccurs with a headword within a window.
 * 
 * Stores the association strength (logDice), raw cooccurrence counts,
 * and the most frequent POS tag for the collocate.
 * 
 * Sorted by logDice descending (higher scores = stronger association).
 */
public record Collocation(
    String lemma,              // The collocating word (lowercase)
    String pos,                // Most frequent POS tag for this lemma
    long cooccurrence,         // Raw cooccurrence count with headword
    long frequency,            // Total corpus frequency of this lemma
    float logDice              // Association score (0-14, higher = stronger)
) implements Comparable<Collocation> {

    /**
     * Compare by logDice descending (highest scores first).
     */
    @Override
    public int compareTo(Collocation other) {
        return Float.compare(other.logDice, this.logDice);
    }

    /**
     * Format for display.
     */
    @Override
    public String toString() {
        return String.format("%s (%s) logDice=%.2f cooc=%d freq=%d",
            lemma, pos, logDice, cooccurrence, frequency);
    }
}

package pl.marcinmilkowski.word_sketch.model.exploration;

import org.jspecify.annotations.NonNull;

/**
 * A collocate that defines the semantic class (shared by multiple discovered nouns).
 */
public record CoreCollocate(
        @NonNull String collocate,
        int sharedByCount,
        int totalNouns,
        double seedLogDice,
        double avgLogDice) {

    /** Coverage ratio: how many of the discovered nouns share this collocate */
    public double coverage() {
        return totalNouns > 0 ? (double) sharedByCount / totalNouns : 0.0;
    }

    @Override
    public String toString() {
        return String.format("%s (in %d/%d nouns, avgLogDice=%.1f)",
            collocate, sharedByCount, totalNouns, avgLogDice);
    }
}

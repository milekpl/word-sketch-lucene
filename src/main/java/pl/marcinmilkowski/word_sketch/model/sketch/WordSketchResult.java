package pl.marcinmilkowski.word_sketch.model.sketch;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Result of a word sketch query containing collocation information.
 */
public record WordSketchResult(
        @NonNull String lemma,
        /** Part-of-speech tag; {@link #UNKNOWN_POS} when the tagger has no information. */
        @NonNull String pos,
        long frequency,
        double logDice, double relativeFrequency,
        @Nullable List<String> examples) {
    /** Sentinel for missing POS information returned by the tagger. */
    public static final String UNKNOWN_POS = "unknown";
}

package pl.marcinmilkowski.word_sketch.utils;

/** Generic math utilities shared across the codebase. */
public final class MathUtils {

    private MathUtils() {}

    /** Rounds {@code value} to two decimal places using half-up rounding. */
    public static double round2dp(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

package pl.marcinmilkowski.word_sketch.exploration;

import java.util.Collection;

/**
 * Shared validation helpers for seed-word collections.
 */
public final class Seeds {

    private Seeds() {}

    /** @throws IllegalArgumentException if fewer than 2 seeds are provided */
    public static void requireAtLeastTwo(Collection<String> seeds, String context) {
        if (seeds.size() < 2) {
            throw new IllegalArgumentException(
                context + " requires at least 2 seeds; received " + seeds.size());
        }
    }
}

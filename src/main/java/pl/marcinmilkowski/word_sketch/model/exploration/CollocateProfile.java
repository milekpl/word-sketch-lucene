package pl.marcinmilkowski.word_sketch.model.exploration;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Profile of one collocate across all seed nouns.
 * Shows graded scores, not just binary presence.
 */
public record CollocateProfile(
        String adjective,
        Map<String, Double> nounScores,
        int presentInCount,
        int totalNouns,
        double avgLogDice,
        double maxLogDice,
        double minLogDice,
        double variance,
        double commonalityScore,
        double distinctivenessScore) {

    /** @return {@code true} when this collocate appears in all {@link #totalNouns} seed nouns */
    public boolean isFullyShared() { return presentInCount == totalNouns; }

    /** @return {@code true} when this collocate appears in at least 2 — but not all — seed nouns */
    public boolean isPartiallyShared() { return presentInCount >= 2 && presentInCount < totalNouns; }

    /** @return {@code true} when this collocate appears in exactly one seed noun */
    public boolean isSpecific() { return presentInCount == 1; }

    /**
     * Returns the sharing category for this collocate profile.
     * Convenience method for type-safe serialization and dispatch without string comparisons.
     */
    public SharingCategory sharingCategory() {
        if (isFullyShared()) return SharingCategory.FULLY_SHARED;
        if (isPartiallyShared()) return SharingCategory.PARTIALLY_SHARED;
        return SharingCategory.SPECIFIC;
    }

    /** Returns the noun this collocate is most strongly associated with, or empty if {@code nounScores} is empty */
    public Optional<String> strongestNoun() {
        return nounScores.entrySet().stream()
            .max(Comparator.comparingDouble(Map.Entry::getValue))
            .map(Map.Entry::getKey);
    }

    @Override
    public String toString() {
        String scoreStr = nounScores.entrySet().stream()
            .map(e -> e.getKey() + ":" + String.format("%.1f", e.getValue()))
            .collect(Collectors.joining(", "));
        return String.format("%s [%d/%d: %s]", adjective, presentInCount, totalNouns, scoreStr);
    }
}

package pl.marcinmilkowski.word_sketch.model;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Profile of one adjective across all seed nouns.
 * Shows graded scores, not just binary presence.
 */
public record AdjectiveProfile(
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

    public boolean isFullyShared() { return presentInCount == totalNouns; }
    public boolean isPartiallyShared() { return presentInCount >= 2 && presentInCount < totalNouns; }
    public boolean isSpecific() { return presentInCount == 1; }

    /** Returns the noun this adjective is most associated with */
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

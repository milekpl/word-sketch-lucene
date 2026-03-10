package pl.marcinmilkowski.word_sketch.model;

import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Profile of one adjective across all seed nouns.
 * Shows graded scores, not just binary presence.
 */
public class AdjectiveProfile {
    public final String adjective;
    public final Map<String, Double> nounScores;  // noun -> logDice (0 if absent)
    public final int presentInCount;              // How many nouns have this adjective
    public final int totalNouns;                  // Total seed nouns
    public final double avgLogDice;               // Average score (where present)
    public final double maxLogDice;               // Highest score
    public final double minLogDice;               // Lowest score (where present)
    public final double variance;                 // Score variance (high = distinctive pattern)
    public final double commonalityScore;         // For ranking shared adjectives
    public final double distinctivenessScore;     // For ranking specific adjectives

    public AdjectiveProfile(String adjective, Map<String, Double> nounScores,
            int presentInCount, int totalNouns,
            double avgLogDice, double maxLogDice, double minLogDice, double variance,
            double commonalityScore, double distinctivenessScore) {
        this.adjective = adjective;
        this.nounScores = nounScores;
        this.presentInCount = presentInCount;
        this.totalNouns = totalNouns;
        this.avgLogDice = avgLogDice;
        this.maxLogDice = maxLogDice;
        this.minLogDice = minLogDice;
        this.variance = variance;
        this.commonalityScore = commonalityScore;
        this.distinctivenessScore = distinctivenessScore;
    }

    public boolean isFullyShared() { return presentInCount == totalNouns; }
    public boolean isPartiallyShared() { return presentInCount >= 2 && presentInCount < totalNouns; }
    public boolean isSpecific() { return presentInCount == 1; }

    /** Get the noun this adjective is most associated with */
    public String getStrongestNoun() {
        return nounScores.entrySet().stream()
            .max(Comparator.comparingDouble(Map.Entry::getValue))
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    @Override
    public String toString() {
        String scoreStr = nounScores.entrySet().stream()
            .map(e -> e.getKey() + ":" + String.format("%.1f", e.getValue()))
            .collect(Collectors.joining(", "));
        return String.format("%s [%d/%d: %s]", adjective, presentInCount, totalNouns, scoreStr);
    }
}

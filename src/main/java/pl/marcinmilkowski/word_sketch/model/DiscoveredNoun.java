package pl.marcinmilkowski.word_sketch.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A noun discovered during exploration - shares collocates with the seed.
 */
public class DiscoveredNoun {
    public final String noun;
    public final Map<String, Double> sharedCollocates;  // collocate -> logDice
    public final int sharedCount;                        // Number of shared collocates
    public final double cumulativeScore;                 // Sum of logDice scores
    public final double avgLogDice;                      // Average logDice
    public final double similarityScore;                 // Ranking score (sharedCount × avgLogDice)

    public DiscoveredNoun(String noun, Map<String, Double> sharedCollocates,
            int sharedCount, double cumulativeScore, double avgLogDice, double similarityScore) {
        this.noun = noun;
        this.sharedCollocates = sharedCollocates;
        this.sharedCount = sharedCount;
        this.cumulativeScore = cumulativeScore;
        this.avgLogDice = avgLogDice;
        this.similarityScore = similarityScore;
    }

    public List<String> getSharedCollocateList() {
        return new ArrayList<>(sharedCollocates.keySet());
    }

    @Override
    public String toString() {
        return String.format("%s (shared=%d, score=%.1f)", noun, sharedCount, similarityScore);
    }
}

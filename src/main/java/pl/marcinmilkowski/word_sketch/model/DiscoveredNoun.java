package pl.marcinmilkowski.word_sketch.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A noun discovered during exploration - shares collocates with the seed.
 */
public record DiscoveredNoun(
        String noun,
        Map<String, Double> sharedCollocates,
        int sharedCount,
        double combinedRelevanceScore,
        double avgLogDice) {

    /** @return an unordered list of the shared collocate lemmas; never null */
    public List<String> sharedCollocateList() {
        return new ArrayList<>(sharedCollocates.keySet());
    }

    @Override
    public String toString() {
        return String.format("%s (shared=%d, score=%.1f)", noun, sharedCount, combinedRelevanceScore);
    }
}

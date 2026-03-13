package pl.marcinmilkowski.word_sketch.model.exploration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NonNull;

/**
 * A noun discovered during exploration - shares collocates with the seed.
 */
public record DiscoveredNoun(
        @NonNull String noun,
        @NonNull Map<String, Double> sharedCollocates,
        int sharedCount,
        double combinedRelevanceScore,
        double avgLogDice) {

    public DiscoveredNoun {
        sharedCollocates = Map.copyOf(sharedCollocates);
    }

    /** @return an unordered list of the shared collocate lemmas; never null */
    public List<String> sharedCollocateList() {
        return new ArrayList<>(sharedCollocates.keySet());
    }

    @Override
    public String toString() {
        return String.format("%s (shared=%d, score=%.1f)", noun, sharedCount, combinedRelevanceScore);
    }
}

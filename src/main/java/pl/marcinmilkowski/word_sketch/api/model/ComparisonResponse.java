package pl.marcinmilkowski.word_sketch.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import pl.marcinmilkowski.word_sketch.api.model.ExploreResponse;
import pl.marcinmilkowski.word_sketch.model.exploration.RelationEdgeType;

/**
 * Typed response record for the collocate-profile comparison endpoint
 * ({@code GET /api/semantic-field/compare}).
 *
 * <p>Using a record instead of {@code Map<String,Object>} enforces the response shape at
 * compile time and makes the JSON contract explicit.</p>
 */
public record ComparisonResponse(
        String status,
        List<String> seeds,
        @JsonProperty("seed_count") int seedCount,
        ExploreResponse.Parameters parameters,
        List<CollocateProfileEntry> collocates,
        @JsonProperty("collocates_count") int collocatesCount,
        @JsonProperty("fully_shared_count") int fullySharedCount,
        @JsonProperty("partially_shared_count") int partiallySharedCount,
        @JsonProperty("specific_count") int specificCount,
        List<EdgeEntry> edges,
        @JsonProperty("edges_count") int edgesCount) {

    /**
     * Per-collocate entry in a comparison response, capturing sharing statistics
     * across the seed nouns.
     */
    public record CollocateProfileEntry(
            String word,
            @JsonProperty("present_in") int presentIn,
            @JsonProperty("total_nouns") int totalNouns,
            @JsonProperty("avg_logdice") double avgLogDice,
            @JsonProperty("max_logdice") double maxLogDice,
            double variance,
            @JsonProperty("commonality_score") double commonalityScore,
            @JsonProperty("distinctiveness_score") double distinctivenessScore,
            String category,
            @JsonProperty("noun_scores") Map<String, Double> nounScores,
            @JsonProperty("specific_to") @com.fasterxml.jackson.annotation.JsonInclude(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String specificTo) {}

    /** Graph edge connecting a collocate to a seed noun. */
    public record EdgeEntry(String source, String target, double weight, RelationEdgeType type) {}
}

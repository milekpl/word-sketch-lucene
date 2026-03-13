package pl.marcinmilkowski.word_sketch.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import pl.marcinmilkowski.word_sketch.api.model.ExploreResponse;

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
        @JsonProperty("edges_count") int edgesCount) {}


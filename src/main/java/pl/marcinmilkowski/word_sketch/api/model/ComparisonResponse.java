package pl.marcinmilkowski.word_sketch.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

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
        Parameters parameters,
        List<CollocateProfileEntry> collocates,
        @JsonProperty("collocates_count") int collocatesCount,
        @JsonProperty("fully_shared_count") int fullySharedCount,
        @JsonProperty("partially_shared_count") int partiallySharedCount,
        @JsonProperty("specific_count") int specificCount,
        List<EdgeEntry> edges,
        @JsonProperty("edges_count") int edgesCount) {

    /**
     * Parameters sub-object for comparison responses.
     * Intentionally omits {@code nouns_per} (which is single-seed-only) so the
     * field does not appear in comparison output.
     */
    public record Parameters(
            String relation,
            int top,
            @JsonProperty("min_shared") int minShared,
            @JsonProperty("min_logdice") double minLogDice) {}
}


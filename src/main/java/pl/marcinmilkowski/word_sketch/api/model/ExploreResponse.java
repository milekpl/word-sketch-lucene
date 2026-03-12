package pl.marcinmilkowski.word_sketch.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Typed response record for single-seed and multi-seed semantic field exploration endpoints
 * ({@code GET /api/semantic-field/explore} and {@code GET /api/semantic-field/explore-multi}).
 *
 * <p>Using a record instead of {@code Map<String,Object>} enforces the response shape at
 * compile time and makes the JSON contract explicit.  Jackson 2.12+ serialises records
 * directly via their component accessors.</p>
 *
 * <p>The {@code seed} field is set for single-seed responses and {@code null} for multi-seed;
 * the {@code seeds} and {@code seedCount} fields are set for multi-seed responses and
 * {@code null} for single-seed.  {@link JsonInclude#NON_NULL} suppresses absent fields so
 * that each response variant remains clean.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExploreResponse(
        String status,

        /** Set for single-seed responses; absent in multi-seed responses. */
        @Nullable String seed,

        /** Set for multi-seed responses; absent in single-seed responses. */
        @Nullable List<String> seeds,

        /** Set for multi-seed responses; absent in single-seed responses. */
        @JsonProperty("seed_count") @Nullable Integer seedCount,

        Parameters parameters,

        @JsonProperty("seed_collocates") List<SeedCollocateEntry> seedCollocates,
        @JsonProperty("seed_collocates_count") int seedCollocatesCount,

        @JsonProperty("discovered_nouns") List<DiscoveredNounEntry> discoveredNouns,
        @JsonProperty("discovered_nouns_count") int discoveredNounsCount,

        @JsonProperty("core_collocates") List<CoreCollocateEntry> coreCollocates,
        @JsonProperty("core_collocates_count") int coreCollocatesCount,

        List<EdgeEntry> edges,
        @JsonProperty("edges_count") int edgesCount) {

    /** Parameters sub-object embedded in every explore response. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Parameters(
            String relation,
            int top,
            @JsonProperty("min_shared") int minShared,
            @JsonProperty("min_logdice") double minLogDice,
            /** Present only in single-seed responses; absent in multi-seed responses. */
            @JsonProperty("nouns_per") @Nullable Integer nounsPer) {}

    /** A single discovered-noun entry in the {@code discovered_nouns} array. */
    public record DiscoveredNounEntry(
            String word,
            @JsonProperty("shared_count") int sharedCount,
            @JsonProperty("similarity_score") double similarityScore,
            @JsonProperty("avg_logdice") double avgLogDice,
            @JsonProperty("shared_collocates") List<String> sharedCollocates) {}

    /** A single core-collocate entry in the {@code core_collocates} array. */
    public record CoreCollocateEntry(
            String word,
            @JsonProperty("shared_by_count") int sharedByCount,
            @JsonProperty("total_nouns") int totalNouns,
            double coverage,
            @JsonProperty("seed_logdice") double seedLogDice) {}

    /** A single directed graph edge in the {@code edges} array. */
    public record EdgeEntry(
            String source,
            String target,
            @JsonProperty("log_dice") double logDice,
            String type) {}
}

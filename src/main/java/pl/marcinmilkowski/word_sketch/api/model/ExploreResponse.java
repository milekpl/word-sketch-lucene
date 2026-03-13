package pl.marcinmilkowski.word_sketch.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;
import pl.marcinmilkowski.word_sketch.model.exploration.RelationEdgeType;

/**
 * Sealed interface for single-seed and multi-seed semantic field exploration responses
 * ({@code GET /api/semantic-field/explore} and {@code GET /api/semantic-field/explore-multi}).
 *
 * <p>The two endpoint variants carry mutually exclusive identity fields — a single {@code seed}
 * string vs. a {@code seeds} list with {@code seed_count} — so they are modelled as distinct
 * record types that share this interface.  Jackson serialises each concrete record directly
 * via its component accessors; no polymorphic type discriminator is required because the two
 * variants are produced by different endpoints and never mixed in the same collection.</p>
 *
 * <p>Use {@link SingleSeed} for single-seed responses and {@link MultiSeed} for multi-seed
 * responses.  Both carry the shared payload fields ({@code seed_collocates},
 * {@code discovered_nouns}, {@code core_collocates}, {@code edges} and their count companions)
 * declared in this interface.</p>
 */
public sealed interface ExploreResponse
        permits ExploreResponse.SingleSeed, ExploreResponse.MultiSeed {

    String status();
    Parameters parameters();

    @JsonProperty("seed_collocates")
    List<SeedCollocateEntry> seedCollocates();

    /**
     * Returns the list of noun entries for this response.
     *
     * <p>Semantics and JSON key differ by variant:
     * <ul>
     *   <li>In {@link SingleSeed}: nouns discovered by reverse collocate expansion from the seed,
     *       serialised as {@code discovered_nouns}.</li>
     *   <li>In {@link MultiSeed}: the caller-supplied source seeds (input words), serialised as
     *       {@code source_seeds} to distinguish them from genuinely reverse-discovered nouns.</li>
     * </ul>
     * Each concrete variant carries its own {@code @JsonProperty} annotation; the interface
     * intentionally omits one to avoid inheriting a misleading key name.
     */
    List<DiscoveredNounEntry> discoveredNouns();

    @JsonProperty("core_collocates")
    List<CoreCollocateEntry> coreCollocates();

    List<EdgeEntry> edges();

    // -------------------------------------------------------------------------
    // Shared nested types
    // -------------------------------------------------------------------------

    /**
     * Parameters sub-object embedded in every explore response.
     *
     * <p>The {@code nouns_per} field is only meaningful for single-seed responses; it is
     * {@code null} (and therefore omitted from JSON via {@link JsonInclude#NON_NULL}) in
     * multi-seed responses.</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Parameters(
            String relation,
            int top,
            @JsonProperty("min_shared") int minShared,
            @JsonProperty("min_logdice") double minLogDice,
            /** Present only in single-seed responses; absent in multi-seed responses. */
            @JsonProperty("nouns_per") @Nullable Integer nounsPer) {}

    /** A single discovered-noun entry in the {@code discovered_nouns} array. */
    record DiscoveredNounEntry(
            String word,
            @JsonProperty("shared_count") int sharedCount,
            @JsonProperty("similarity_score") double similarityScore,
            @JsonProperty("avg_logdice") double avgLogDice,
            @JsonProperty("shared_collocates") List<String> sharedCollocates) {}

    /** A single core-collocate entry in the {@code core_collocates} array. */
    record CoreCollocateEntry(
            String word,
            @JsonProperty("shared_by_count") int sharedByCount,
            @JsonProperty("total_nouns") int totalNouns,
            double coverage,
            @JsonProperty("seed_logdice") double seedLogDice) {}

    /** A single directed graph edge in the {@code edges} array. */
    record EdgeEntry(
            String source,
            String target,
            @JsonProperty("log_dice") double logDice,
            RelationEdgeType type) {}

    // -------------------------------------------------------------------------
    // Concrete variants
    // -------------------------------------------------------------------------

    /**
     * Single-seed response: carries {@code seed}; never carries {@code seeds} or
     * {@code seed_count}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SingleSeed(
            String status,
            String seed,
            Parameters parameters,
            @JsonProperty("seed_collocates") List<SeedCollocateEntry> seedCollocates,
            @JsonProperty("discovered_nouns") List<DiscoveredNounEntry> discoveredNouns,
            @JsonProperty("core_collocates") List<CoreCollocateEntry> coreCollocates,
            List<EdgeEntry> edges
    ) implements ExploreResponse {}

    /**
     * Multi-seed response: carries {@code seeds} and {@code seed_count}; never carries
     * {@code seed}.
     *
     * <p>The {@code source_seeds} / {@code source_seeds_count} fields carry per-seed collocate
     * statistics for each input seed.  They are named differently from {@link SingleSeed}'s
     * {@code discovered_nouns}: in single-seed mode those are genuinely newly discovered nouns
     * found via reverse collocate expansion, whereas here the entries are the caller-supplied
     * seed words — a distinct JSON key prevents misreading them as reverse-discovered nouns.</p>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MultiSeed(
            String status,
            List<String> seeds,
            Parameters parameters,
            @JsonProperty("seed_collocates") List<SeedCollocateEntry> seedCollocates,
            @JsonProperty("source_seeds") List<DiscoveredNounEntry> sourceSeeds,
            @JsonProperty("core_collocates") List<CoreCollocateEntry> coreCollocates,
            List<EdgeEntry> edges
    ) implements ExploreResponse {
        @JsonProperty("seed_count")
        public int seedCount() { return seeds().size(); }

        /** Delegates to {@link #sourceSeeds()} to satisfy the shared interface contract. */
        @Override
        @JsonProperty("source_seeds")
        public List<DiscoveredNounEntry> discoveredNouns() { return sourceSeeds(); }
    }
}

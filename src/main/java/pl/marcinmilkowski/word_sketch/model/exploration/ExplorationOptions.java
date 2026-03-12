package pl.marcinmilkowski.word_sketch.model.exploration;

/**
 * Shared exploration options for multi-seed exploration
 * ({@code SemanticFieldExplorer#exploreMultiSeed}) and collocate profile comparison
 * ({@code SemanticFieldExplorer#compareCollocateProfiles}).
 *
 * <p>Single-seed exploration passes the additional {@code reverseExpansionLimit} parameter
 * directly to {@code ExplorationService.exploreByPattern} rather than via a subtype of this
 * record.</p>
 */
public record ExplorationOptions(
        /** Maximum collocates to retrieve per seed in the first lookup pass. */
        int topCollocates,
        /** Minimum logDice score threshold; collocates below this value are discarded. */
        double minLogDice,
        /** Minimum number of shared collocates required for a noun to be included in results. */
        int minShared) {
}

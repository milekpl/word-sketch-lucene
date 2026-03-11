package pl.marcinmilkowski.word_sketch.model;

/**
 * Shared exploration options for multi-seed exploration
 * ({@code SemanticFieldExplorer#exploreMultiSeed}) and collocate profile comparison
 * ({@code SemanticFieldExplorer#compareCollocateProfiles}).
 *
 * <p>Single-seed exploration uses {@link SingleSeedExplorationOptions}, which adds the
 * {@code nounsPerCollocate} parameter specific to the reverse-lookup phase.</p>
 */
public record ExplorationOptions(
        /** Maximum collocates to retrieve per seed in the first lookup pass. */
        int topCollocates,
        /** Minimum logDice score threshold; collocates below this value are discarded. */
        double minLogDice,
        /** Minimum number of shared collocates required for a noun to be included in results. */
        int minShared) {
}

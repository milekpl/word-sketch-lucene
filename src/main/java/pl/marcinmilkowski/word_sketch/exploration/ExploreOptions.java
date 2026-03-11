package pl.marcinmilkowski.word_sketch.exploration;

/**
 * Options for {@code SemanticFieldExplorer#exploreByPattern}, bundling the tuning parameters
 * that were previously spread across multiple method arguments.
 *
 * <p>Note: the {@code Explore} prefix (vs {@code Exploration} used by {@link
 * pl.marcinmilkowski.word_sketch.model.ExplorationResult}) is historical; the asymmetry is
 * intentional rather than a naming error.</p>
 *
 * <p>{@code nounsPerCollocate} is only applicable to single-seed exploration
 * ({@code exploreByPattern}). It is not used in multi-seed exploration
 * ({@code exploreMultiSeed}), which accepts its parameters directly.</p>
 */
public record ExploreOptions(
        /** Maximum collocates to retrieve per seed in the first lookup pass. */
        int topCollocates,
        /**
         * Maximum nouns to expand per collocate (adjective) in the reverse lookup phase.
         * Applies to single-seed exploration only; pass 0 (via {@link #forMultiSeed}) for multi-seed.
         */
        int nounsPerCollocate,
        /** Minimum logDice score threshold; collocates below this value are discarded. */
        double minLogDice,
        /** Minimum number of shared collocates required for a noun to be included in results. */
        int minShared) {

    /**
     * Factory for multi-seed exploration options. Sets {@code nounsPerCollocate=0} explicitly
     * since the multi-seed path does not perform per-collocate noun reverse-lookup.
     */
    public static ExploreOptions forMultiSeed(int top, double minLogDice, int minShared) {
        return new ExploreOptions(top, 0, minLogDice, minShared);
    }
}

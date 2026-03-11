package pl.marcinmilkowski.word_sketch.exploration;

/**
 * Options for {@code SemanticFieldExplorer#exploreByPattern}, bundling the tuning parameters
 * that were previously spread across multiple method arguments.
 *
 * <p>Note: the {@code Explore} prefix (vs {@code Exploration} used by {@link
 * pl.marcinmilkowski.word_sketch.model.ExplorationResult}) is historical; the asymmetry is
 * intentional rather than a naming error.</p>
 *
 * <p>{@code nounsPerSeed} is only applicable to single-seed exploration
 * ({@code exploreByPattern}). It is not used in multi-seed exploration
 * ({@code exploreMultiSeed}), which accepts its parameters directly.</p>
 */
public record ExploreOptions(
        /** Maximum collocates to retrieve per seed in the first lookup pass. */
        int topCollocates,
        /** Maximum nouns to expand per seed adjective. Applies to single-seed exploration only. */
        int nounsPerSeed,
        /** Minimum logDice score threshold; collocates below this value are discarded. */
        double minLogDice,
        /** Minimum number of shared collocates required for a noun to be included in results. */
        int minShared) {}

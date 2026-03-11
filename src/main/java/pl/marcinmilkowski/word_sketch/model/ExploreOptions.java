package pl.marcinmilkowski.word_sketch.model;

/**
 * Options for {@code SemanticFieldExplorer#exploreByPattern}, bundling the tuning parameters
 * that were previously spread across multiple method arguments.
 *
 * <p>Note: the {@code Explore} prefix (vs {@code Exploration} used by {@link ExplorationResult})
 * is historical; the asymmetry is intentional rather than a naming error.</p>
 */
public record ExploreOptions(
        int topCollocates,
        int nounsPerSeed,
        double minLogDice,
        int minShared,
        boolean includeExamples) {}

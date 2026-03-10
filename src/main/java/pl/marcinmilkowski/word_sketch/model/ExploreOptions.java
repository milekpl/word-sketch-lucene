package pl.marcinmilkowski.word_sketch.model;

/**
 * Options for {@code SemanticFieldExplorer#exploreByPattern}, bundling the tuning parameters and
 * positional hints that were previously spread across 10 method arguments.
 */
public record ExploreOptions(
        int topCollocates,
        int nounsPerCollocate,
        double minLogDice,
        int minShared,
        boolean includeExamples,
        int headPos,
        int collocatePos) {}

package pl.marcinmilkowski.word_sketch.model.exploration;

/**
 * Shared exploration options for multi-seed exploration
 * ({@code SemanticFieldExplorer#exploreMultiSeed}) and collocate profile comparison
 * ({@code SemanticFieldExplorer#compareCollocateProfiles}).
 *
 * <p>Single-seed exploration uses {@link SingleSeedExplorationOptions}, which wraps this
 * record and adds the {@code reverseExpansionLimit} field specific to that code path.</p>
 */
public record ExplorationOptions(
        /** Maximum collocates to retrieve per seed in the first lookup pass. */
        int topCollocates,
        /** Minimum logDice filter threshold; collocates below this value are discarded. */
        double logDiceThreshold,
        /** Minimum number of shared collocates required for a noun to be included in results. */
        int minShared) {
}

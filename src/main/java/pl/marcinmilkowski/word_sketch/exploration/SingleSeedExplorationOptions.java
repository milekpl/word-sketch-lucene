package pl.marcinmilkowski.word_sketch.exploration;

/**
 * Options for single-seed semantic field exploration
 * ({@code SemanticFieldExplorer#exploreByPattern}).
 *
 * <p>Composes {@link ExplorationOptions} (shared base parameters) with
 * {@code nounsPerCollocate}, which is only meaningful for the single-seed
 * reverse-lookup phase. Multi-seed exploration and profile comparison use
 * {@link ExplorationOptions} directly.</p>
 */
public record SingleSeedExplorationOptions(
        /** Shared exploration parameters (top collocates, min logDice, min shared). */
        ExplorationOptions base,
        /**
         * Maximum nouns to expand per collocate in the reverse lookup phase.
         * Specific to single-seed exploration — not used in multi-seed or comparison.
         */
        int nounsPerCollocate) {

    /** Maximum collocates to retrieve per seed in the first lookup pass. */
    public int topCollocates() { return base.topCollocates(); }

    /** Minimum logDice score threshold; collocates below this value are discarded. */
    public double minLogDice() { return base.minLogDice(); }

    /** Minimum number of shared collocates required for a noun to be included in results. */
    public int minShared() { return base.minShared(); }

    /** Project to a shared {@link ExplorationOptions}, dropping the single-seed-only field. */
    public ExplorationOptions toBaseOptions() {
        return base;
    }
}

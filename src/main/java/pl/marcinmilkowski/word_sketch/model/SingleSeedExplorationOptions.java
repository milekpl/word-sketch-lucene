package pl.marcinmilkowski.word_sketch.model;

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
}

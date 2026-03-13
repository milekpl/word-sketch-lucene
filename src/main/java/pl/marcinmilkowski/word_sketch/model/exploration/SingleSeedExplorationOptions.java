package pl.marcinmilkowski.word_sketch.model.exploration;

/**
 * Exploration options for single-seed exploration, extending the common options
 * ({@link ExplorationOptions}) with {@code reverseExpansionLimit} (the "nouns_per" HTTP
 * parameter), which controls the number of noun candidates expanded per collocate in the
 * reverse-lookup pass and is specific to the single-seed code path.
 *
 * <p>Multi-seed and comparison methods accept plain {@link ExplorationOptions}; using a
 * distinct type here makes the single-seed call site self-documenting and prevents the
 * {@code reverseExpansionLimit} value from silently being ignored when passed to those
 * methods.</p>
 *
 * <p>The three delegation methods ({@link #topCollocates()}, {@link #logDiceThreshold()},
 * {@link #minShared()}) are kept for call-site convenience so consumers do not need to
 * unwrap {@link #base()} to read the common parameters.</p>
 *
 * @param base                  common exploration tuning parameters shared across all modes
 * @param reverseExpansionLimit maximum noun candidates to expand per collocate in the
 *                              reverse-lookup pass (HTTP param: {@code nouns_per});
 *                              tunes breadth of discovered-noun set
 */
public record SingleSeedExplorationOptions(
        ExplorationOptions base,
        int reverseExpansionLimit) {

    /** @return maximum collocates to retrieve per seed. */
    public int topCollocates()  { return base.topCollocates(); }
    /** @return minimum logDice threshold for collocate inclusion. */
    public double logDiceThreshold()  { return base.logDiceThreshold(); }
    /** @return minimum shared-by count for discovered nouns. */
    public int minShared()      { return base.minShared(); }
}

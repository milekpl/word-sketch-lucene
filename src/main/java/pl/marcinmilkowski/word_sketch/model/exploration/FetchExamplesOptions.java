package pl.marcinmilkowski.word_sketch.model.exploration;

/**
 * Options for {@link pl.marcinmilkowski.word_sketch.exploration.spi.ExplorationService#fetchExamples}.
 *
 * <p>Matches the options-record convention used by sibling methods:
 * {@link ExplorationOptions} (multi-seed / comparison) and
 * {@link SingleSeedExplorationOptions} (single-seed exploration).</p>
 *
 * @param maxExamples maximum number of deduplicated concordance sentences to return;
 *                    the actual count may be lower when fewer unique sentences exist
 */
public record FetchExamplesOptions(int maxExamples) {

    /** Convenience factory with the standard API default of 10 examples. */
    public static FetchExamplesOptions withDefault() {
        return new FetchExamplesOptions(10);
    }
}

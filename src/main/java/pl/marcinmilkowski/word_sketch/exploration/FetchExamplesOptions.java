package pl.marcinmilkowski.word_sketch.exploration;

/**
 * Options for {@link SemanticFieldExplorer#fetchExamples}.
 *
 * <p>Follows the options-object pattern used by sibling methods
 * ({@link ExplorationOptions}, {@link SingleSeedExplorationOptions}) so that
 * callers can extend the parameter set without breaking binary compatibility.</p>
 */
public record FetchExamplesOptions(int maxExamples) {

    public FetchExamplesOptions {
        if (maxExamples < 1) throw new IllegalArgumentException("maxExamples must be >= 1");
    }
}

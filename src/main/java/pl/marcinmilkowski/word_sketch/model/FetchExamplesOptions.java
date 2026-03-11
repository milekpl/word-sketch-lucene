package pl.marcinmilkowski.word_sketch.model;

/**
 * Options for the {@code SemanticFieldExplorer#fetchExamples} method.
 *
 * <p>Follows the options-object pattern used by sibling types
 * ({@link ExplorationOptions}, {@link SingleSeedExplorationOptions}) so that
 * callers can extend the parameter set without breaking binary compatibility.</p>
 */
public record FetchExamplesOptions(int maxExamples) {

    public FetchExamplesOptions {
        if (maxExamples < 1) throw new IllegalArgumentException("maxExamples must be >= 1");
    }
}

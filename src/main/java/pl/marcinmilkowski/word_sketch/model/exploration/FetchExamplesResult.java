package pl.marcinmilkowski.word_sketch.model.exploration;

import java.util.List;
import pl.marcinmilkowski.word_sketch.model.QueryResults;

/**
 * Return value of {@link pl.marcinmilkowski.word_sketch.exploration.ExplorationService#fetchExamples},
 * bundling the concordance results together with the BCQL pattern that was used to retrieve them.
 *
 * <p>Carrying the pattern here prevents callers from independently re-constructing it,
 * which previously led to the pattern being built twice with no shared contract.</p>
 */
public record FetchExamplesResult(List<QueryResults.CollocateResult> examples, String bcqlPattern) {}

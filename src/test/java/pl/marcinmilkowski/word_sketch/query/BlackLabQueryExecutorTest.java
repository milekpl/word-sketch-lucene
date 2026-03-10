package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BlackLabQueryExecutor.
 * Most methods require a live BlackLab index.
 * TODO: Add tests using an in-memory index or mock BlackLab searcher.
 */
@Disabled("Requires live BlackLab index — integration tests TBD")
class BlackLabQueryExecutorTest {
    // TODO: Add unit tests for:
    // - scoreHits() scoring logic using ConcordanceResult.forCollocate()
    // - findCollocations() throws IllegalArgumentException for missing lemma
    // - getTotalFrequency() propagates exceptions
}

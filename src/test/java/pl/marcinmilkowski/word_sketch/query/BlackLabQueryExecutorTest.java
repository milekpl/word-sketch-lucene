package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BlackLabQueryExecutor.
 *
 * <p>BlackLabQueryExecutor wraps a live BlackLab index; all its public methods
 * require an on-disk index to operate.  These tests are skipped automatically
 * when no index is available.  To enable them, provide a valid BlackLab index
 * directory via the {@code CONCEPT_SKETCH_TEST_INDEX} environment variable or
 * the {@code conceptSketch.testIndex} system property.
 */
@Disabled("Requires a live BlackLab index — set CONCEPT_SKETCH_TEST_INDEX env var and remove @Disabled to run")
class BlackLabQueryExecutorTest {

    private static final String INDEX_PATH = System.getenv("CONCEPT_SKETCH_TEST_INDEX") != null
            ? System.getenv("CONCEPT_SKETCH_TEST_INDEX")
            : System.getProperty("conceptSketch.testIndex");

    @BeforeAll
    static void requireIndex() {
        Assumptions.assumeTrue(INDEX_PATH != null, "No index path configured — set CONCEPT_SKETCH_TEST_INDEX to enable");
        Path path = Path.of(INDEX_PATH);
        Assumptions.assumeTrue(path.toFile().exists(),
            "Requires live BlackLab index at " + path + " — set CONCEPT_SKETCH_TEST_INDEX to enable");
    }

    @Test
    void findCollocations_missingLemma_throwsIllegalArgumentException() throws Exception {
        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            // A cqlPattern that is neither a placeholder (%s) nor starts with '[' is invalid.
            assertThrows(IllegalArgumentException.class, () ->
                executor.findCollocations("house", "INVALID_PATTERN_FORMAT", 0.0, 10));
        }
    }

    @Test
    void findCollocations_validLemmaAndPattern_returnsNonNullList() throws Exception {
        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            var results = executor.findCollocations("house", "[xpos=\"JJ.*\"]", 0.0, 10);
            assertNotNull(results, "Result list must not be null");
        }
    }

    @Test
    void getTotalFrequency_knownLemma_returnsPositiveCount() throws Exception {
        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            long freq = executor.getTotalFrequency("theory");
            assertTrue(freq > 0, "Frequency of a common lemma should be positive");
        }
    }
}


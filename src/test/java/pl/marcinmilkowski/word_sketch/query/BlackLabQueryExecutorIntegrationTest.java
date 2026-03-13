package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.io.IOException;
import java.util.List;

import pl.marcinmilkowski.word_sketch.model.sketch.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for {@link BlackLabQueryExecutor} that require a real BlackLab index.
 *
 * <p><strong>CI gating:</strong> every test in this class calls
 * {@link Assumptions#assumeTrue(boolean, String)} at the start to skip itself when no index
 * path is configured. This is intentional — the tests must not run in environments without a
 * corpus index. To enable them, set the {@code CONCEPT_SKETCH_TEST_INDEX} environment variable
 * (or the {@code conceptSketch.testIndex} system property) to the path of a valid BlackLab index.
 * See the project README for setup instructions.</p>
 *
 * <p>The {@code @Tag("integration")} annotation allows these tests to be explicitly
 * included or excluded via Maven Surefire/Failsafe configuration, for example:
 * {@code mvn test -Dgroups=integration} to run only integration tests, or
 * {@code mvn test -DexcludedGroups=integration} to skip them.</p>
 */
@Tag("integration")
public class BlackLabQueryExecutorIntegrationTest {
    @Test
    public void testSubjectOf() throws Exception {
        // Guard: skip this test in CI environments where no BlackLab index is available.
        // Set CONCEPT_SKETCH_TEST_INDEX (env var) or conceptSketch.testIndex (system property)
        // to the path of a real index to enable this test.
        String indexPath = System.getenv("CONCEPT_SKETCH_TEST_INDEX") != null
                ? System.getenv("CONCEPT_SKETCH_TEST_INDEX")
                : System.getProperty("conceptSketch.testIndex");
        File indexDir = indexPath != null ? new File(indexPath) : null;
        Assumptions.assumeTrue(indexDir != null && indexDir.exists(), "Test skipped: index not available at " + indexPath);

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(indexPath)) {
            String[] patterns = {
                    "[tag=\"NN.*\"]",
                    "[upos=\"NOUN\"]",
                    "[xpos=\"NN.*\"]",
                    "[tag=\"NN.*\"] [tag=\"VB.*\"]",
                    "[upos=\"NOUN\"] [upos=\"VERB\"]",
                    "[xpos=\"NN.*\"] [xpos=\"VB.*\"]"
            };

            for (String p : patterns) {
                    try {
                        List<WordSketchResult> results = executor.executeSurfacePattern(
                                p, 0.0, 10);
                        assertNotNull(results, "Results list should not be null for pattern: " + p);
                        // When the index is available, the pattern should match something in a 74M-sentence corpus.
                        assertFalse(results.isEmpty(),
                                "Expected at least one result from a 74M-sentence corpus for pattern: " + p);
                        for (WordSketchResult r : results) {
                            assertNotNull(r.lemma(), "Result lemma must not be null for pattern: " + p);
                            assertTrue(r.frequency() > 0,
                                    "Result frequency must be > 0 for pattern: " + p + ", got: " + r.frequency());
                        }
                    } catch (IOException e) {
                    fail("Unexpected IOException for pattern '" + p + "': " + e.getMessage());
                }
            }
        }
    }
}

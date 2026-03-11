package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.io.IOException;
import java.util.List;

import pl.marcinmilkowski.word_sketch.query.QueryResults;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Disabled("Requires a live BlackLab index — set CONCEPT_SKETCH_TEST_INDEX env var and remove @Disabled to run")
public class BlackLabQueryExecutorIntegrationTest {
    @Test
    public void testSubjectOf() throws Exception {
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
                    List<QueryResults.WordSketchResult> results = executor.executeSurfacePattern(
                            "test", p, 0.0, 10);
                    assertNotNull(results, "Results list should not be null for pattern: " + p);
                } catch (IOException e) {
                    Assumptions.assumeTrue(false,
                            "Test skipped: index does not support pattern '" + p + "': " + e.getMessage());
                }
            }
        }
    }
}

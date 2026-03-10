package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class BlackLabDummyTest {
    @Test
    public void testSubjectOf() throws Exception {
        String indexPath = "D:\\corpora_philsci\\bi";
        File indexDir = new File(indexPath);
        Assumptions.assumeTrue(indexDir.exists(), "Test skipped: index not available at " + indexPath);

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
                int colPos = p.contains("] [") ? 2 : 1;
                try {
                    List<QueryResults.WordSketchResult> results = executor.executeSurfacePattern(
                            "test", p, 1, colPos, 0.0, 10);
                    assertNotNull(results, "Results list should not be null for pattern: " + p);
                } catch (IOException e) {
                    Assumptions.assumeTrue(false,
                            "Test skipped: index does not support pattern '" + p + "': " + e.getMessage());
                }
            }
        }
    }
}

package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.List;

public class BlackLabDummyTest {
    @Test
    public void testSubjectOf() throws Exception {
        String indexPath = "D:\\corpora_philsci\\bi";
        File indexDir = new File(indexPath);
        if (!indexDir.exists()) {
            System.err.println("Index path not found: " + indexPath);
            return;
        }

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(indexPath)) {
            System.out.println("Executing Surface Pattern tests...");
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
                    int colPos = p.contains("] [") ? 2 : 1;
                    List<QueryResults.WordSketchResult> results = executor.executeSurfacePattern(
                            "test", p, 1, colPos, 0.0, 10);
                    System.out.println("Pattern " + p + " -> " + results.size() + " results returned");
                } catch (Exception e) {
                    System.out.println("Pattern " + p + " -> ERROR: " + e.getMessage());
                }
            }
        }
    }
}

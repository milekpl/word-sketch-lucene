package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.io.File;
import java.util.List;
import pl.marcinmilkowski.word_sketch.query.QueryResults;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for dependency relation queries using BlackLab backend.
 *
 * These tests verify that the executeDependencyPattern() method
 * correctly queries dependency relations from the CoNLL-U indexed corpus.
 *
 * To run these tests, set the INDEX_PATH environment variable or
 * modify the indexPath field to point to a valid BlackLab index.
 */
@Disabled("Requires a live BlackLab index — set CONCEPT_SKETCH_TEST_INDEX env var and remove @Disabled to run")
public class DependencySketchTest {

    private static final String INDEX_PATH = System.getenv("CONCEPT_SKETCH_TEST_INDEX") != null
            ? System.getenv("CONCEPT_SKETCH_TEST_INDEX")
            : System.getProperty("conceptSketch.testIndex");

    @Test
    public void testDependencyNsubjQuery() throws Exception {
        File indexDir = INDEX_PATH != null ? new File(INDEX_PATH) : null;
        Assumptions.assumeTrue(indexDir != null && indexDir.exists(), "Skipping: index not found at " + INDEX_PATH);

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            List<QueryResults.WordSketchResult> results = executor.executeDependencyPattern(
                "theory", "nsubj", 0.0, 20
            );

            assertNotNull(results);
        }
    }

    @Test
    public void testDependencyAmodQuery() throws Exception {
        File indexDir = INDEX_PATH != null ? new File(INDEX_PATH) : null;
        Assumptions.assumeTrue(indexDir != null && indexDir.exists(), "Skipping: index not found at " + INDEX_PATH);

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            List<QueryResults.WordSketchResult> results = executor.executeDependencyPattern(
                "theory", "amod", 0.0, 20
            );

            assertNotNull(results);
        }
    }

    @Test
    public void testDependencyObjQuery() throws Exception {
        File indexDir = INDEX_PATH != null ? new File(INDEX_PATH) : null;
        Assumptions.assumeTrue(indexDir != null && indexDir.exists(), "Skipping: index not found at " + INDEX_PATH);

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            List<QueryResults.WordSketchResult> results = executor.executeDependencyPattern(
                "explain", "obj", 0.0, 20
            );

            assertNotNull(results);
        }
    }

    @Test
    public void testDependencyWithPosConstraint() throws Exception {
        File indexDir = INDEX_PATH != null ? new File(INDEX_PATH) : null;
        Assumptions.assumeTrue(indexDir != null && indexDir.exists(), "Skipping: index not found at " + INDEX_PATH);

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            List<QueryResults.WordSketchResult> results = executor.executeDependencyPatternWithPos(
                "theory", "nsubj", 0.0, 20, "NN.*"
            );

            assertNotNull(results);
        }
    }

    @Test
    public void testDependencyEmptyLemma() throws Exception {
        File indexDir = INDEX_PATH != null ? new File(INDEX_PATH) : null;
        Assumptions.assumeTrue(indexDir != null && indexDir.exists(), "Skipping: index not found at " + INDEX_PATH);

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            List<QueryResults.WordSketchResult> results = executor.executeDependencyPattern(
                "", "nsubj", 0.0, 20
            );

            assertTrue(results.isEmpty(), "Empty lemma should return empty results");
        }
    }

    @Test
    public void testDependencyNullDeprel() throws Exception {
        File indexDir = INDEX_PATH != null ? new File(INDEX_PATH) : null;
        Assumptions.assumeTrue(indexDir != null && indexDir.exists(), "Skipping: index not found at " + INDEX_PATH);

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            List<QueryResults.WordSketchResult> results = executor.executeDependencyPattern(
                "theory", null, 0.0, 20
            );

            assertTrue(results.isEmpty(), "Null deprel should return empty results");
        }
    }
}

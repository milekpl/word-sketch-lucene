package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.io.File;
import java.util.List;
import pl.marcinmilkowski.word_sketch.model.sketch.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for dependency relation queries using BlackLab backend.
 *
 * <p>These tests verify that the {@code executeDependencyPattern()} method correctly
 * queries dependency relations from the CoNLL-U indexed corpus.</p>
 *
 * <p>The {@code @Tag("integration")} annotation allows these tests to be explicitly
 * included or excluded. Use {@code mvn test -Dgroups=integration} to run only
 * integration tests, or {@code mvn test -DexcludedGroups=integration} to skip them.
 * A live BlackLab index is required: set {@code CONCEPT_SKETCH_TEST_INDEX} environment
 * variable to point to a valid index directory.</p>
 */
@Tag("integration")
public class DependencySketchTest {

    private static final String INDEX_PATH = System.getenv("CONCEPT_SKETCH_TEST_INDEX") != null
            ? System.getenv("CONCEPT_SKETCH_TEST_INDEX")
            : System.getProperty("conceptSketch.testIndex");

    @Test
    public void testDependencyNsubjQuery() throws Exception {
        File indexDir = INDEX_PATH != null ? new File(INDEX_PATH) : null;
        Assumptions.assumeTrue(indexDir != null && indexDir.exists(), "Skipping: index not found at " + INDEX_PATH);

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            List<WordSketchResult> results = executor.executeDependencyPattern(
                "theory", "nsubj", 0.0, 20, null
            );

            assertNotNull(results, "Results list must not be null");
            assertFalse(results.isEmpty(), "Expected at least one nsubj collocate for high-frequency lemma 'theory'");
            for (WordSketchResult r : results) {
                assertNotNull(r.lemma(), "Each result must have a non-null lemma");
                assertTrue(r.frequency() > 0, "Each result must have frequency > 0, got: " + r.frequency());
            }
        }
    }

    @Test
    public void testDependencyAmodQuery() throws Exception {
        File indexDir = INDEX_PATH != null ? new File(INDEX_PATH) : null;
        Assumptions.assumeTrue(indexDir != null && indexDir.exists(), "Skipping: index not found at " + INDEX_PATH);

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            List<WordSketchResult> results = executor.executeDependencyPattern(
                "theory", "amod", 0.0, 20, null
            );

            assertNotNull(results, "Results list must not be null");
            assertFalse(results.isEmpty(), "Expected at least one amod collocate for high-frequency lemma 'theory'");
            for (WordSketchResult r : results) {
                assertNotNull(r.lemma(), "Each result must have a non-null lemma");
                assertTrue(r.frequency() > 0, "Each result must have frequency > 0, got: " + r.frequency());
            }
        }
    }

    @Test
    public void testDependencyObjQuery() throws Exception {
        File indexDir = INDEX_PATH != null ? new File(INDEX_PATH) : null;
        Assumptions.assumeTrue(indexDir != null && indexDir.exists(), "Skipping: index not found at " + INDEX_PATH);

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            List<WordSketchResult> results = executor.executeDependencyPattern(
                "explain", "obj", 0.0, 20, null
            );

            assertNotNull(results, "Results list must not be null");
            assertFalse(results.isEmpty(), "Expected at least one obj collocate for high-frequency lemma 'explain'");
            for (WordSketchResult r : results) {
                assertNotNull(r.lemma(), "Each result must have a non-null lemma");
                assertTrue(r.frequency() > 0, "Each result must have frequency > 0, got: " + r.frequency());
            }
        }
    }

    @Test
    public void testDependencyWithPosConstraint() throws Exception {
        File indexDir = INDEX_PATH != null ? new File(INDEX_PATH) : null;
        Assumptions.assumeTrue(indexDir != null && indexDir.exists(), "Skipping: index not found at " + INDEX_PATH);

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            List<WordSketchResult> results = executor.executeDependencyPattern(
                "theory", "nsubj", 0.0, 20, "NN.*"
            );

            assertNotNull(results, "Results list must not be null");
            assertFalse(results.isEmpty(), "Expected at least one nsubj+NN.* collocate for 'theory'");
            for (WordSketchResult r : results) {
                assertNotNull(r.lemma(), "Each result must have a non-null lemma");
                assertTrue(r.frequency() > 0, "Each result must have frequency > 0, got: " + r.frequency());
            }
        }
    }

    @Test
    public void testDependencyEmptyLemma() throws Exception {
        File indexDir = INDEX_PATH != null ? new File(INDEX_PATH) : null;
        Assumptions.assumeTrue(indexDir != null && indexDir.exists(), "Skipping: index not found at " + INDEX_PATH);

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            List<WordSketchResult> results = executor.executeDependencyPattern(
                "", "nsubj", 0.0, 20, null
            );

            assertTrue(results.isEmpty(), "Empty lemma should return empty results");
        }
    }

    @Test
    public void testDependencyNullDeprel() throws Exception {
        File indexDir = INDEX_PATH != null ? new File(INDEX_PATH) : null;
        Assumptions.assumeTrue(indexDir != null && indexDir.exists(), "Skipping: index not found at " + INDEX_PATH);

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            List<WordSketchResult> results = executor.executeDependencyPattern(
                "theory", null, 0.0, 20, null
            );

            assertTrue(results.isEmpty(), "Null deprel should return empty results");
        }
    }
}

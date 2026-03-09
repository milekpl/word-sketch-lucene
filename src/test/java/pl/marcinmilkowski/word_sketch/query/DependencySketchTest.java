package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.List;

/**
 * Test for dependency relation queries using BlackLab backend.
 * 
 * These tests verify that the executeDependencyPattern() method
 * correctly queries dependency relations from the CoNLL-U indexed corpus.
 * 
 * To run these tests, set the INDEX_PATH environment variable or 
 * modify the indexPath field to point to a valid BlackLab index.
 */
public class DependencySketchTest {

    // Update this path to point to your test index
    private static final String INDEX_PATH = "D:\\corpora_philsci\\bi";

    @Test
    public void testDependencyNsubjQuery() throws Exception {
        File indexDir = new File(INDEX_PATH);
        if (!indexDir.exists()) {
            System.err.println("Index path not found: " + INDEX_PATH);
            System.err.println("Skipping dependency query test.");
            return;
        }

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            System.out.println("Testing dependency nsubj relation...");
            
            // Test: Find subjects of "theory"
            List<QueryResults.WordSketchResult> results = executor.executeDependencyPattern(
                "theory",      // lemma
                "nsubj",       // dependency relation
                null,          // no POS constraint
                0.0,           // min logDice
                20             // max results
            );
            
            System.out.println("nsubj query for 'theory' returned " + results.size() + " results");
            
            for (QueryResults.WordSketchResult result : results) {
                System.out.printf("  - %s (freq: %d, logDice: %.2f)%n", 
                    result.getLemma(), result.getFrequency(), result.getLogDice());
            }
            
            // Basic assertions
            assert results != null : "Results should not be null";
            // Note: We don't assert on size because it depends on the corpus
        }
    }

    @Test
    public void testDependencyAmodQuery() throws Exception {
        File indexDir = new File(INDEX_PATH);
        if (!indexDir.exists()) {
            System.err.println("Index path not found: " + INDEX_PATH);
            System.err.println("Skipping dependency query test.");
            return;
        }

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            System.out.println("Testing dependency amod relation...");
            
            // Test: Find adjectival modifiers of "theory"
            List<QueryResults.WordSketchResult> results = executor.executeDependencyPattern(
                "theory",      // lemma
                "amod",        // dependency relation
                null,          // no POS constraint
                0.0,           // min logDice
                20             // max results
            );
            
            System.out.println("amod query for 'theory' returned " + results.size() + " results");
            
            for (QueryResults.WordSketchResult result : results) {
                System.out.printf("  - %s (freq: %d, logDice: %.2f)%n", 
                    result.getLemma(), result.getFrequency(), result.getLogDice());
            }
        }
    }

    @Test
    public void testDependencyObjQuery() throws Exception {
        File indexDir = new File(INDEX_PATH);
        if (!indexDir.exists()) {
            System.err.println("Index path not found: " + INDEX_PATH);
            System.err.println("Skipping dependency query test.");
            return;
        }

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            System.out.println("Testing dependency obj relation...");
            
            // Test: Find objects of "explain"
            List<QueryResults.WordSketchResult> results = executor.executeDependencyPattern(
                "explain",     // lemma
                "obj",         // dependency relation
                null,          // no POS constraint
                0.0,           // min logDice
                20             // max results
            );
            
            System.out.println("obj query for 'explain' returned " + results.size() + " results");
            
            for (QueryResults.WordSketchResult result : results) {
                System.out.printf("  - %s (freq: %d, logDice: %.2f)%n", 
                    result.getLemma(), result.getFrequency(), result.getLogDice());
            }
        }
    }

    @Test
    public void testDependencyWithPosConstraint() throws Exception {
        File indexDir = new File(INDEX_PATH);
        if (!indexDir.exists()) {
            System.err.println("Index path not found: " + INDEX_PATH);
            System.err.println("Skipping dependency query test.");
            return;
        }

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            System.out.println("Testing dependency query with POS constraint...");
            
            // Test: Find subjects of verbs (not nouns)
            List<QueryResults.WordSketchResult> results = executor.executeDependencyPattern(
                "theory",      // lemma
                "nsubj",       // dependency relation
                "NN.*",        // POS constraint (only nouns as subjects)
                0.0,           // min logDice
                20             // max results
            );
            
            System.out.println("nsubj query for 'theory' with NN.* constraint returned " + results.size() + " results");
            
            for (QueryResults.WordSketchResult result : results) {
                System.out.printf("  - %s (freq: %d, logDice: %.2f, pos: %s)%n", 
                    result.getLemma(), result.getFrequency(), result.getLogDice(), result.getPos());
            }
        }
    }

    @Test
    public void testDependencyEmptyLemma() throws Exception {
        File indexDir = new File(INDEX_PATH);
        if (!indexDir.exists()) {
            System.err.println("Index path not found: " + INDEX_PATH);
            System.err.println("Skipping dependency query test.");
            return;
        }

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            System.out.println("Testing dependency query with empty lemma...");
            
            // Test: Empty lemma should return empty results
            List<QueryResults.WordSketchResult> results = executor.executeDependencyPattern(
                "",            // empty lemma
                "nsubj",       // dependency relation
                null,          // no POS constraint
                0.0,           // min logDice
                20             // max results
            );
            
            assert results.isEmpty() : "Empty lemma should return empty results";
            System.out.println("Empty lemma test passed: returned " + results.size() + " results");
        }
    }

    @Test
    public void testDependencyNullDeprel() throws Exception {
        File indexDir = new File(INDEX_PATH);
        if (!indexDir.exists()) {
            System.err.println("Index path not found: " + INDEX_PATH);
            System.err.println("Skipping dependency query test.");
            return;
        }

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            System.out.println("Testing dependency query with null deprel...");
            
            // Test: Null deprel should return empty results
            List<QueryResults.WordSketchResult> results = executor.executeDependencyPattern(
                "theory",      // lemma
                null,          // null deprel
                null,          // no POS constraint
                0.0,           // min logDice
                20             // max results
            );
            
            assert results.isEmpty() : "Null deprel should return empty results";
            System.out.println("Null deprel test passed: returned " + results.size() + " results");
        }
    }
}

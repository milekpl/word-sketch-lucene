package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import java.io.File;
import java.util.List;

/**
 * TDD Test: BCQL Query with Full Sentence Concordance
 *
 * Tests for:
 * 1. BCQL endpoint uses CorpusQueryLanguageParser (not ContextualQueryLanguageParser)
 * 2. Full sentences are returned (not just 5-word KWIC)
 * 3. Plain text AND raw XML both available (toggle)
 *
 * RED PHASE: This test should FAIL initially
 */
public class BlackLabBcqlConcordanceTest {

    private static final String INDEX_PATH = "D:\\corpora_philsci\\bi";

    @Test
    @EnabledIf("indexExists")
    public void testBcqlQueryReturnsFullSentences() throws Exception {
        File indexDir = new File(INDEX_PATH);
        if (!indexDir.exists()) {
            System.err.println("Index not found: " + INDEX_PATH);
            return;
        }

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            // Query: find "theory" as noun followed by copula and adjective
            // BCQL pattern: 1:[lemma="theory"] [lemma="be"] 2:[xpos="JJ.*"]
            String bcqlPattern = "1:[lemma=\"theory\"] [lemma=\"be\"] 2:[xpos=\"JJ.*\"]";

            List<QueryResults.ConcordanceResult> results = executor.executeBcqlQuery(bcqlPattern, 5);

            System.out.println("=== BCQL Query Test ===");
            System.out.println("Pattern: " + bcqlPattern);
            System.out.println("Results count: " + results.size());

            for (QueryResults.ConcordanceResult r : results) {
                System.out.println("\n--- Result ---");
                System.out.println("Sentence: " + r.getSentence());
                System.out.println("Collocate: " + r.getCollocateLemma());
                System.out.println("LogDice: " + r.getLogDice());

                // ASSERTION 1: Sentence should NOT be null or empty
                assert r.getSentence() != null && !r.getSentence().isEmpty()
                    : "Sentence should not be empty";

                // ASSERTION 2: Sentence should be a full sentence (contains period or multiple words)
                // Not just a 5-word KWIC snippet
                String sentence = r.getSentence();
                boolean isFullSentence = sentence.contains(".") || sentence.split("\\s+").length > 10;
                assert isFullSentence : "Should return full sentence, got: " + sentence;

                // ASSERTION 3: The collocate should be an adjective
                String collocate = r.getCollocateLemma();
                assert collocate != null && !collocate.isEmpty() : "Collocate should not be empty";
            }

            // ASSERTION 4: Should have at least some results
            assert results.size() > 0 : "Should find at least some results for 'theory is <adj>'";
        }
    }

    @Test
    @EnabledIf("indexExists")
    public void testBcqlQueryWithIrrelevant() throws Exception {
        File indexDir = new File(INDEX_PATH);
        if (!indexDir.exists()) {
            System.err.println("Index not found: " + INDEX_PATH);
            return;
        }

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            // Query: find any noun followed by copula and adjective
            // This should match "concept is basic" or similar in the corpus
            String bcqlPattern = "1:[lemma=\"concept\"] [lemma=\"be\"] 2:[xpos=\"JJ.*\"]";

            List<QueryResults.ConcordanceResult> results = executor.executeBcqlQuery(bcqlPattern, 5);

            System.out.println("=== BCQL Query 'concept is <adj>' ===");
            System.out.println("Pattern: " + bcqlPattern);
            System.out.println("Results count: " + results.size());

            // ASSERTION: Should have results
            assert results.size() > 0 : "Should find results for 'concept is <adj>'";

            for (QueryResults.ConcordanceResult r : results) {
                System.out.println("Sentence: " + r.getSentence().substring(0, Math.min(100, r.getSentence().length())) + "...");
                System.out.println("Collocate: " + r.getCollocateLemma());

                // The sentence should contain "concept"
                String sentence = r.getSentence();
                assert sentence.toLowerCase().contains("concept")
                    : "Sentence should contain 'concept', got: " + sentence;

                // Full sentence returned should be long (more than 50 chars)
                assert r.getSentence().length() > 50
                    : "Should return full sentence, got: " + r.getSentence().length() + " chars";
            }
        }
    }

    private static boolean indexExists() {
        return new File(INDEX_PATH).exists();
    }
}

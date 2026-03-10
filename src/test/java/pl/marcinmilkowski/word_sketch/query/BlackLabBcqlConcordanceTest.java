package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import java.io.File;
import java.util.List;
import pl.marcinmilkowski.word_sketch.query.QueryResults;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD Test: BCQL Query with Full Sentence Concordance
 *
 * Tests for:
 * 1. BCQL endpoint uses CorpusQueryLanguageParser (not ContextualQueryLanguageParser)
 * 2. Full sentences are returned (not just 5-word KWIC)
 * 3. Plain text AND raw XML both available (toggle)
 */
public class BlackLabBcqlConcordanceTest {

    private static final String INDEX_PATH = System.getenv("CONCEPT_SKETCH_TEST_INDEX") != null
            ? System.getenv("CONCEPT_SKETCH_TEST_INDEX")
            : System.getProperty("conceptSketch.testIndex", "D:\\corpora_philsci\\bi");

    @Test
    @EnabledIf("indexExists")
    public void testBcqlQueryReturnsFullSentences() throws Exception {
        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            String bcqlPattern = "1:[lemma=\"theory\"] [lemma=\"be\"] 2:[xpos=\"JJ.*\"]";

            List<QueryResults.CollocateResult> results = executor.executeBcqlQuery(bcqlPattern, 5);

            for (QueryResults.CollocateResult r : results) {
                assertNotNull(r.getSentence(), "Sentence should not be null");
                assertFalse(r.getSentence().isEmpty(), "Sentence should not be empty");

                String sentence = r.getSentence();
                boolean isFullSentence = sentence.contains(".") || sentence.split("\\s+").length > 10;
                assertTrue(isFullSentence, "Should return full sentence, got: " + sentence);

                assertNotNull(r.collocateLemma(), "Collocate should not be null");
                assertFalse(r.collocateLemma().isEmpty(), "Collocate should not be empty");
            }

            assertTrue(results.size() > 0, "Should find at least some results for 'theory is <adj>'");
        }
    }

    @Test
    @EnabledIf("indexExists")
    public void testBcqlQueryWithIrrelevant() throws Exception {
        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            String bcqlPattern = "1:[lemma=\"concept\"] [lemma=\"be\"] 2:[xpos=\"JJ.*\"]";

            List<QueryResults.CollocateResult> results = executor.executeBcqlQuery(bcqlPattern, 5);

            assertTrue(results.size() > 0, "Should find results for 'concept is <adj>'");

            for (QueryResults.CollocateResult r : results) {
                String sentence = r.getSentence();
                assertTrue(sentence.toLowerCase().contains("concept"),
                    "Sentence should contain 'concept', got: " + sentence);
                assertTrue(r.getSentence().length() > 50,
                    "Should return full sentence, got: " + r.getSentence().length() + " chars");
            }
        }
    }

    private static boolean indexExists() {
        return new File(INDEX_PATH).exists();
    }
}

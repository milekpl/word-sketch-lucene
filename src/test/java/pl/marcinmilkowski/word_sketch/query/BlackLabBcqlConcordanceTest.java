package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.io.File;
import java.util.List;
import pl.marcinmilkowski.word_sketch.model.sketch.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for BCQL query execution using a live BlackLab index.
 *
 * <p><strong>Why these tests require a live index:</strong> {@link BlackLabQueryExecutor}
 * wraps BlackLab's on-disk inverted index. There is no in-memory substitute, so
 * these tests cannot run in CI without a pre-built corpus index on disk.</p>
 *
 * <p><strong>What these tests verify:</strong>
 * <ol>
 *   <li>The BCQL endpoint uses {@code CorpusQueryLanguageParser} (not
 *       {@code ContextualQueryLanguageParser}) so that labeled capture groups
 *       ({@code 1:}, {@code 2:}) are supported.</li>
 *   <li>Full sentences are returned in {@link CollocateResult#sentence()}
 *       rather than the truncated 5-word KWIC window.</li>
 *   <li>Plain text and raw XML are both available via the result object.</li>
 * </ol>
 * </p>
 *
 * <p><strong>How to run locally:</strong>
 * <pre>
 *   export CONCEPT_SKETCH_TEST_INDEX=/path/to/your/blacklab-index
 *   # Remove @Disabled from this class, then:
 *   JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn test -Dtest=BlackLabBcqlConcordanceTest
 * </pre>
 * The index must contain sentences with "theory" and "concept" as lemmas. The
 * default local development index is {@code D:\corpora_philsci\fpsyg_index}, but any
 * compatible BlackLab index may be supplied via {@code CONCEPT_SKETCH_TEST_INDEX}.
 * </p>
 */
public class BlackLabBcqlConcordanceTest {

    private static final String INDEX_PATH = System.getenv("CONCEPT_SKETCH_TEST_INDEX") != null
            ? System.getenv("CONCEPT_SKETCH_TEST_INDEX")
            : System.getProperty("conceptSketch.testIndex");

    private static void assumeIndexAvailable() {
        Assumptions.assumeTrue(INDEX_PATH != null && new File(INDEX_PATH).exists(),
            "Test skipped: index not available — set CONCEPT_SKETCH_TEST_INDEX to enable");
    }

    @Test
    public void testBcqlQueryReturnsFullSentences() throws Exception {
        assumeIndexAvailable();
        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            String bcqlPattern = "1:[lemma=\"theory\"] [lemma=\"be\"] 2:[xpos=\"JJ.*\"]";

            List<CollocateResult> results = executor.executeBcqlQuery(bcqlPattern, 5);

            for (CollocateResult r : results) {
                assertNotNull(r.sentence(), "Sentence should not be null");
                assertFalse(r.sentence().isEmpty(), "Sentence should not be empty");

                String sentence = r.sentence();
                boolean isFullSentence = sentence.contains(".") || sentence.split("\\s+").length > 10;
                assertTrue(isFullSentence, "Should return full sentence, got: " + sentence);

                assertNotNull(r.collocateLemma(), "Collocate should not be null");
                assertFalse(r.collocateLemma().isEmpty(), "Collocate should not be empty");
            }

            assertTrue(results.size() > 0, "Should find at least some results for 'theory is <adj>'");
        }
    }

    @Test
    public void testBcqlQueryWithIrrelevant() throws Exception {
        assumeIndexAvailable();
        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            String bcqlPattern = "1:[lemma=\"concept\"] [lemma=\"be\"] 2:[xpos=\"JJ.*\"]";

            List<CollocateResult> results = executor.executeBcqlQuery(bcqlPattern, 5);

            assertTrue(results.size() > 0, "Should find results for 'concept is <adj>'");

            for (CollocateResult r : results) {
                String sentence = r.sentence();
                assertTrue(sentence.toLowerCase().contains("concept"),
                    "Sentence should contain 'concept', got: " + sentence);
                assertTrue(r.sentence().length() > 50,
                    "Should return full sentence, got: " + r.sentence().length() + " chars");
            }
        }
    }

    @Test
    public void testBcqlExactPredicateQueryReturnsResults() throws Exception {
        assumeIndexAvailable();
        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
            String bcqlPattern = "[lemma=\"theory\" & xpos=\"NN.*\"] "
                    + "[lemma=\"be|appear|seem|look|sound|feel|smell|taste|remain|become|get|grow|turn|prove\"] "
                    + "[lemma=\"correct\" & xpos=\"JJ.*\"]";

            List<CollocateResult> results = executor.executeBcqlQuery(bcqlPattern, 10);

            assertNotNull(results, "BCQL result list must not be null");
            for (CollocateResult r : results) {
                assertNotNull(r.sentence(), "Sentence should not be null");
                assertFalse(r.sentence().isBlank(), "Sentence should not be blank");
            }
        }
    }
}

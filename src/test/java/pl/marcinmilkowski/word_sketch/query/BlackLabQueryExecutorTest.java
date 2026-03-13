package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BlackLabQueryExecutor}.
 *
 * <p>Static helper method tests run in every CI environment without any index.
 * The {@link LiveIndex} nested class is skipped automatically when no index
 * is available — provide one via {@code CONCEPT_SKETCH_TEST_INDEX} or
 * the {@code conceptSketch.testIndex} system property to enable those tests.</p>
 */
@DisplayName("BlackLabQueryExecutor")
class BlackLabQueryExecutorTest {

    // ── Static helper tests (no index required, always run in CI) ────────────

    @Test
    @DisplayName("buildBcqlWithLemmaPrepended: bracket-starting pattern is prefixed with quoted lemma")
    void buildBcql_bracketPattern_prependsLemma() {
        String result = BlackLabQueryExecutor.buildBcqlWithLemmaPrepended("[xpos=\"JJ.*\"]", "house");
        assertEquals("\"house\" [xpos=\"JJ.*\"]", result);
    }

    @Test
    @DisplayName("buildBcqlWithLemmaPrepended: lemma is lowercased")
    void buildBcql_lemmaIsLowercased() {
        String result = BlackLabQueryExecutor.buildBcqlWithLemmaPrepended("[xpos=\"NN\"]", "Theory");
        assertTrue(result.startsWith("\"theory\""), "Lemma must be lowercased in BCQL pattern: " + result);
    }

    @Test
    @DisplayName("buildBcqlWithLemmaPrepended: backslash in lemma is escaped")
    void buildBcql_backslashEscaped() {
        String result = BlackLabQueryExecutor.buildBcqlWithLemmaPrepended("[xpos=\"NN\"]", "back\\slash");
        assertTrue(result.startsWith("\"back\\\\slash\""), "Backslash must be doubled: " + result);
    }

    @Test
    @DisplayName("buildBcqlWithLemmaPrepended: non-bracket pattern throws IllegalArgumentException")
    void buildBcql_nonBracketPattern_throwsIAE() {
        assertThrows(IllegalArgumentException.class, () ->
            BlackLabQueryExecutor.buildBcqlWithLemmaPrepended("INVALID_FORMAT", "house"));
    }

    @Test
    @DisplayName("buildBcqlWithLemmaPrepended: empty-string pattern throws IllegalArgumentException")
    void buildBcql_emptyPattern_throwsIAE() {
        assertThrows(IllegalArgumentException.class, () ->
            BlackLabQueryExecutor.buildBcqlWithLemmaPrepended("", "house"));
    }

    @Test
    @DisplayName("buildBcqlWithLemmaPrepended: null pattern throws IllegalArgumentException")
    void buildBcql_nullPattern_throwsIAE() {
        assertThrows(IllegalArgumentException.class, () ->
            BlackLabQueryExecutor.buildBcqlWithLemmaPrepended(null, "house"));
    }

    // ── Guard-clause tests (no index required, always run in CI) ─────────────

    @Test
    @DisplayName("executeCollocations: null lemma throws IllegalArgumentException")
    void executeCollocations_nullLemma_returnsEmptyList() {
        BlackLabQueryExecutor executor = new BlackLabQueryExecutor((nl.inl.blacklab.search.BlackLabIndex) null);
        assertThrows(IllegalArgumentException.class, () ->
            executor.executeCollocations(null, "[xpos=\"JJ.*\"]", 0.0, 10),
            "Null lemma must throw IllegalArgumentException");
    }

    @Test
    @DisplayName("executeCollocations: empty lemma throws IllegalArgumentException")
    void executeCollocations_emptyLemma_returnsEmptyList() {
        BlackLabQueryExecutor executor = new BlackLabQueryExecutor((nl.inl.blacklab.search.BlackLabIndex) null);
        assertThrows(IllegalArgumentException.class, () ->
            executor.executeCollocations("", "[xpos=\"JJ.*\"]", 0.0, 10),
            "Empty lemma must throw IllegalArgumentException");
    }

    @Test
    @DisplayName("executeCollocations: valid lemma with null pattern throws IllegalArgumentException")
    void executeCollocations_nullPattern_throwsIAE() {
        BlackLabQueryExecutor executor = new BlackLabQueryExecutor((nl.inl.blacklab.search.BlackLabIndex) null);
        assertThrows(IllegalArgumentException.class, () ->
            executor.executeCollocations("house", null, 0.0, 10));
    }

    // ── Live-index tests (skipped in CI when no index is present) ────────────

    @Nested
    @DisplayName("LiveIndex — requires CONCEPT_SKETCH_TEST_INDEX")
    class LiveIndex {

        private static final String INDEX_PATH = System.getenv("CONCEPT_SKETCH_TEST_INDEX") != null
                ? System.getenv("CONCEPT_SKETCH_TEST_INDEX")
                : System.getProperty("conceptSketch.testIndex");

        @BeforeAll
        static void requireIndex() {
            Assumptions.assumeTrue(INDEX_PATH != null,
                "No index path configured — set CONCEPT_SKETCH_TEST_INDEX to enable");
            Path path = Path.of(INDEX_PATH);
            Assumptions.assumeTrue(path.toFile().exists(),
                "Requires live BlackLab index at " + path + " — set CONCEPT_SKETCH_TEST_INDEX to enable");
        }

        @Test
        void executeCollocations_invalidPattern_throwsIllegalArgumentException() throws Exception {
            try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
                assertThrows(IllegalArgumentException.class, () ->
                    executor.executeCollocations("house", "INVALID_PATTERN_FORMAT", 0.0, 10));
            }
        }

        @Test
        void executeCollocations_validLemmaAndPattern_returnsNonNullList() throws Exception {
            try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
                var results = executor.executeCollocations("house", "[xpos=\"JJ.*\"]", 0.0, 10);
                assertNotNull(results, "Result list must not be null");
            }
        }

        @Test
        void getTotalFrequency_knownLemma_returnsPositiveCount() throws Exception {
            try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(INDEX_PATH)) {
                long freq = executor.getTotalFrequency("theory");
                assertTrue(freq > 0, "Frequency of a common lemma should be positive");
            }
        }
    }
}

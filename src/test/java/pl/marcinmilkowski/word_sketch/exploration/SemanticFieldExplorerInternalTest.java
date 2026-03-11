package pl.marcinmilkowski.word_sketch.exploration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.model.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the package-private 5-param {@code SemanticFieldExplorer#exploreByPattern} overload.
 * Lives in the {@code exploration} package to access package-private visibility.
 */
@DisplayName("SemanticFieldExplorerInternal")
class SemanticFieldExplorerInternalTest {

    private static class StubExecutor implements QueryExecutor {

        private final Map<String, List<QueryResults.WordSketchResult>> collocations;

        StubExecutor(Map<String, List<QueryResults.WordSketchResult>> collocations) {
            this.collocations = collocations;
        }

        @Override
        public List<QueryResults.WordSketchResult> findCollocations(
                String lemma, String cqlPattern, double minLogDice, int maxResults) {
            return collocations.getOrDefault(lemma.toLowerCase(), Collections.emptyList());
        }

        @Override
        public List<QueryResults.ConcordanceResult> executeCqlQuery(String cqlPattern, int maxResults) {
            return Collections.emptyList();
        }

        @Override
        public long getTotalFrequency(String lemma) {
            return 0;
        }

        @Override
        public List<QueryResults.CollocateResult> executeBcqlQuery(String bcqlPattern, int maxResults) {
            return Collections.emptyList();
        }

        @Override
        public List<QueryResults.WordSketchResult> executeSurfacePattern(
                String lemma, String bcqlPattern,
                double minLogDice, int maxResults) {
            return collocations.getOrDefault(lemma.toLowerCase(), Collections.emptyList());
        }

        @Override
        public void close() {}

        @Override
        public List<QueryResults.WordSketchResult> executeDependencyPattern(
                String lemma, String deprel, double minLogDice, int maxResults) {
            return Collections.emptyList();
        }

        @Override
        public List<QueryResults.WordSketchResult> executeDependencyPatternWithPos(
                String lemma, String deprel,
                double minLogDice, int maxResults, String headPosConstraint) {
            return Collections.emptyList();
        }
    }

    private static class RecordingExecutor extends StubExecutor {

        final List<String> capturedCqlPatterns = new ArrayList<>();

        RecordingExecutor(Map<String, List<QueryResults.WordSketchResult>> collocations) {
            super(collocations);
        }

        @Override
        public List<QueryResults.WordSketchResult> findCollocations(
                String lemma, String cqlPattern, double minLogDice, int maxResults) {
            capturedCqlPatterns.add(cqlPattern);
            return super.findCollocations(lemma, cqlPattern, minLogDice, maxResults);
        }
    }

    private static QueryResults.WordSketchResult wsr(String lemma, double logDice) {
        return new QueryResults.WordSketchResult(lemma, "JJ", 10, logDice, 0.0, Collections.emptyList());
    }

    @Test
    @DisplayName("exploreByPattern: returns non-null result for known seed")
    void exploreByPattern_returnsNonNullResult() throws IOException {
        StubExecutor executor = new StubExecutor(Map.of(
            "theory", List.of(wsr("empirical", 8.0), wsr("scientific", 7.0))
        ));
        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor);
        ExploreOptions opts = new ExploreOptions(10, 5, 0.0, 1);
        ExplorationResult result = explorer.exploreByPattern(
            "theory", "test-relation",
            "[lemma=\"theory\"] [xpos=\"JJ.*\"]",
            "[xpos=\"JJ.*\"]",
            opts);
        assertNotNull(result, "Result should not be null");
        assertEquals("theory", result.getSeed(), "Result seed should match input");
    }

    @Test
    @DisplayName("exploreByPattern: seed collocates map contains expected entries")
    void exploreByPattern_seedCollocatesContainExpected() throws IOException {
        StubExecutor executor = new StubExecutor(Map.of(
            "theory", List.of(wsr("empirical", 8.0), wsr("scientific", 7.0))
        ));
        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor);
        ExploreOptions opts = new ExploreOptions(10, 5, 0.0, 1);
        ExplorationResult result = explorer.exploreByPattern(
            "theory", "test-relation",
            "[lemma=\"theory\"] [xpos=\"JJ.*\"]",
            "[xpos=\"JJ.*\"]",
            opts);
        assertNotNull(result.getSeedCollocates(), "Seed collocates map should not be null");
        assertTrue(result.getSeedCollocates().containsKey("empirical"), "Seed collocates should contain 'empirical'");
    }

    @Test
    @DisplayName("deriveNounCqlConstraint: falls back to [xpos=\"NN.*\"] when grammarConfig is null")
    void deriveNounCqlConstraint_fallsBackToBuiltInPattern() throws IOException {
        RecordingExecutor executor = new RecordingExecutor(Map.of(
            "theory", List.of(wsr("empirical", 8.0))
        ));
        // null GrammarConfig → fallback noun constraint
        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, null);
        ExploreOptions opts = new ExploreOptions(10, 1, 0.0, 5);

        explorer.exploreByPattern(
            "theory", "test-relation",
            "[lemma=\"theory\"] [xpos=\"JJ.*\"]",
            "[xpos=\"JJ.*\"]",
            opts);

        // The noun-constraint call is the second findCollocations call (first is for seed collocates)
        assertTrue(executor.capturedCqlPatterns.stream()
            .anyMatch(p -> p.contains("NN")),
            "Fallback noun constraint should contain 'NN'; got: " + executor.capturedCqlPatterns);
    }

    @Test
    @DisplayName("deriveNounCqlConstraint: uses noun pattern from config when available")
    void deriveNounCqlConstraint_withNounRelation_usesConfigPattern() throws IOException {
        // Build a GrammarConfig with a NOUN-collocate relation via the loader
        String json = """
            {
              "version": "1.0-test",
              "relations": [
                {
                  "id": "subj",
                  "name": "Subject",
                  "pattern": "1:[xpos=\\"VB.*\\"] 2:[xpos=\\"NN.*\\"]",
                  "head_position": 1,
                  "collocate_position": 2,
                  "relation_type": "SURFACE"
                }
              ]
            }
            """;
        GrammarConfig config = GrammarConfigLoader.fromReader(new StringReader(json));

        RecordingExecutor executor = new RecordingExecutor(Map.of(
            "theory", List.of(wsr("important", 8.0))
        ));
        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, config);
        ExploreOptions opts = new ExploreOptions(10, 1, 0.0, 5);

        explorer.exploreByPattern(
            "theory", "test-relation",
            "[lemma=\"theory\"] [xpos=\"JJ.*\"]",
            "[xpos=\"JJ.*\"]",
            opts);

        // The noun lookup uses collocateReversePattern() from the first NOUN relation: [xpos="NN.*"]
        assertTrue(executor.capturedCqlPatterns.stream()
            .anyMatch(p -> p.contains("NN")),
            "Config-derived noun constraint should contain 'NN'; got: " + executor.capturedCqlPatterns);
    }
}

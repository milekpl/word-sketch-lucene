package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer;
import pl.marcinmilkowski.word_sketch.model.AdjectiveProfile;
import pl.marcinmilkowski.word_sketch.query.QueryResults;
import pl.marcinmilkowski.word_sketch.model.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.model.Edge;
import pl.marcinmilkowski.word_sketch.model.ExploreOptions;
import pl.marcinmilkowski.word_sketch.model.ExplorationResult;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SemanticFieldExplorer using a stub QueryExecutor.
 * No real index is required.
 */
@DisplayName("SemanticFieldExplorer")
class SemanticFieldExplorerTest {

    // ── Stub QueryExecutor ────────────────────────────────────────────────────

    /**
     * Minimal stub that returns pre-defined adjective lists per noun.
     * The compare() method calls executor.findCollocations(noun, ADJECTIVE_PATTERN, ...).
     */
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
        public List<QueryResults.ConcordanceResult> executeQuery(String cqlPattern, int maxResults) {
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
                String lemma, String deprel, String headPosConstraint,
                double minLogDice, int maxResults) {
            return Collections.emptyList();
        }
    }

    /** Convenience factory for WordSketchResult. */
    private static QueryResults.WordSketchResult wsr(String lemma, double logDice) {
        return new QueryResults.WordSketchResult(lemma, "JJ", 10, logDice, 0.0, Collections.emptyList());
    }

    // ── compare() – intersection logic ───────────────────────────────────────

    @Test
    @DisplayName("compare: adjectives shared by all seeds are fully-shared")
    void compare_sharedAdjectives() throws IOException {
        StubExecutor executor = new StubExecutor(Map.of(
            "theory",     List.of(wsr("empirical", 8.0), wsr("new", 7.0), wsr("old", 6.0)),
            "model",      List.of(wsr("empirical", 7.5), wsr("new", 6.5), wsr("simple", 5.0)),
            "hypothesis", List.of(wsr("empirical", 7.0), wsr("new", 6.0), wsr("bold", 5.5))
        ));

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor);
        ComparisonResult result =
            explorer.compareCollocateProfiles(Set.of("theory", "model", "hypothesis"), 0.0, 50);

        List<AdjectiveProfile> fullyShared = result.getFullyShared();
        List<String> sharedNames = fullyShared.stream()
            .map(p -> p.adjective()).toList();

        assertTrue(sharedNames.contains("empirical"),
            "empirical should be fully shared; got: " + sharedNames);
        assertTrue(sharedNames.contains("new"),
            "new should be fully shared; got: " + sharedNames);
    }

    @Test
    @DisplayName("compare: adjectives specific to one seed are detected as specific")
    void compare_specificAdjectives() throws IOException {
        StubExecutor executor = new StubExecutor(Map.of(
            "theory",  List.of(wsr("abstract", 8.0)),
            "model",   List.of(wsr("mathematical", 7.0))
        ));

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor);
        ComparisonResult result =
            explorer.compareCollocateProfiles(Set.of("theory", "model"), 0.0, 50);

        List<AdjectiveProfile> specific = result.getSpecific();
        List<String> specificNames = specific.stream().map(p -> p.adjective()).toList();

        assertTrue(specificNames.contains("abstract"),
            "abstract should be specific to theory; got: " + specificNames);
        assertTrue(specificNames.contains("mathematical"),
            "mathematical should be specific to model; got: " + specificNames);
    }

    @Test
    @DisplayName("compare: empty seed set returns empty ComparisonResult")
    void compare_emptySeedSet() throws IOException {
        StubExecutor executor = new StubExecutor(Map.of());

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor);
        ComparisonResult result =
            explorer.compareCollocateProfiles(Collections.emptySet(), 0.0, 50);

        assertNotNull(result);
        assertTrue(result.getNouns().isEmpty(), "Expected no nouns in empty result");
        assertTrue(result.getAllAdjectives().isEmpty(), "Expected no adjectives in empty result");
    }

    @Test
    @DisplayName("compare: single seed returns adjectives as specific (no sharing possible)")
    void compare_singleSeed() throws IOException {
        StubExecutor executor = new StubExecutor(Map.of(
            "theory", List.of(wsr("empirical", 8.0), wsr("scientific", 7.5))
        ));

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor);
        ComparisonResult result =
            explorer.compareCollocateProfiles(Set.of("theory"), 0.0, 50);

        assertNotNull(result);
        assertEquals(1, result.getNouns().size());
        // With only one noun all adjectives are "specific" (presentInCount == 1 == totalNouns,
        // so isFullyShared() is also true; the important thing is results are non-empty)
        assertFalse(result.getAllAdjectives().isEmpty(),
            "Should have adjective profiles for the single seed");
    }

    @Test
    @DisplayName("compare: seed with no collocates produces empty profile for that noun")
    void compare_seedWithNoCollocates() throws IOException {
        StubExecutor executor = new StubExecutor(Map.of(
            "theory", List.of(wsr("empirical", 8.0)),
            "model",  Collections.emptyList()   // no collocates
        ));

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor);
        ComparisonResult result =
            explorer.compareCollocateProfiles(Set.of("theory", "model"), 0.0, 50);

        // empirical is specific to theory (model has no adjectives)
        List<String> specificNames = result.getSpecific().stream()
            .map(p -> p.adjective()).toList();
        assertTrue(specificNames.contains("empirical"),
            "empirical should be specific when model has no adjectives; got: " + specificNames);
    }

    @Test
    @DisplayName("compare: null seed set returns empty ComparisonResult")
    void compare_nullSeedSet() throws IOException {
        StubExecutor executor = new StubExecutor(Map.of());

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor);
        ComparisonResult result =
            explorer.compareCollocateProfiles(null, 0.0, 50);

        assertNotNull(result);
        assertTrue(result.getNouns().isEmpty());
    }

    // ── ComparisonResult edge cases ───────────────────────────────────────────

    @Test
    @DisplayName("compare: partially shared adjectives are identified correctly")
    void compare_partiallySharedAdjectives() throws IOException {
        // "theoretical" appears in theory + model but not hypothesis
        StubExecutor executor = new StubExecutor(Map.of(
            "theory",     List.of(wsr("theoretical", 8.0), wsr("empirical", 7.0)),
            "model",      List.of(wsr("theoretical", 7.5), wsr("simple", 6.0)),
            "hypothesis", List.of(wsr("empirical", 7.0), wsr("bold", 5.0))
        ));

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor);
        ComparisonResult result =
            explorer.compareCollocateProfiles(Set.of("theory", "model", "hypothesis"), 0.0, 50);

        List<String> partialNames = result.getPartiallyShared().stream()
            .map(p -> p.adjective()).toList();

        assertTrue(partialNames.contains("theoretical"),
            "theoretical (in 2/3 nouns) should be partially shared; got: " + partialNames);
    }

    @Test
    @DisplayName("compare: edges reflect correct noun-adjective weights")
    void compare_edgeWeights() throws IOException {
        StubExecutor executor = new StubExecutor(Map.of(
            "theory", List.of(wsr("abstract", 9.0)),
            "model",  List.of(wsr("abstract", 6.0))
        ));

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor);
        ComparisonResult result =
            explorer.compareCollocateProfiles(Set.of("theory", "model"), 0.0, 50);

        List<Edge> edges = result.getEdges();
        assertFalse(edges.isEmpty(), "Should have edges");

        // Edge from abstract → theory should have weight ~9.0
        Edge theoryEdge = edges.stream()
            .filter(e -> e.target().equals("theory") && e.source().equals("abstract"))
            .findFirst().orElse(null);
        assertNotNull(theoryEdge, "Should have abstract→theory edge");
        assertEquals(9.0, theoryEdge.weight(), 0.001);
    }
}

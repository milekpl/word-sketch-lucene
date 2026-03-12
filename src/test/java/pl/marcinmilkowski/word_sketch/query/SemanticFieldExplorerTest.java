package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer;
import pl.marcinmilkowski.word_sketch.model.AdjectiveProfile;
import pl.marcinmilkowski.word_sketch.model.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.QueryResults;

import java.io.IOException;
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
     * The compare() method calls executor.executeCollocations(noun, ADJECTIVE_PATTERN, ...).
     */
    private static class StubExecutor implements QueryExecutor {

        private final Map<String, List<QueryResults.WordSketchResult>> collocations;

        StubExecutor(Map<String, List<QueryResults.WordSketchResult>> collocations) {
            this.collocations = collocations;
        }

        @Override
        public List<QueryResults.WordSketchResult> executeCollocations(
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
                String bcqlPattern,
                double minLogDice, int maxResults) {
            String lemma = BlackLabSnippetParser.extractHeadword(bcqlPattern);
            if (lemma == null) lemma = "";
            return collocations.getOrDefault(lemma.toLowerCase(), Collections.emptyList());
        }

        @Override
        public void close() {}

        @Override
        public List<QueryResults.WordSketchResult> executeDependencyPattern(
                String lemma, String deprel,
                double minLogDice, int maxResults, String headPosConstraint) {
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

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, null);
        ComparisonResult result =
            explorer.compareCollocateProfiles(Set.of("theory", "model", "hypothesis"), new pl.marcinmilkowski.word_sketch.model.ExplorationOptions(50, 0.0, 1));

        List<AdjectiveProfile> fullyShared = result.fullyShared();
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

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, null);
        ComparisonResult result =
            explorer.compareCollocateProfiles(Set.of("theory", "model"), new pl.marcinmilkowski.word_sketch.model.ExplorationOptions(50, 0.0, 1));

        List<AdjectiveProfile> specific = result.specific();
        List<String> specificNames = specific.stream().map(p -> p.adjective()).toList();

        assertTrue(specificNames.contains("abstract"),
            "abstract should be specific to theory; got: " + specificNames);
        assertTrue(specificNames.contains("mathematical"),
            "mathematical should be specific to model; got: " + specificNames);
    }

    @Test
    @DisplayName("compare: empty seed set throws IllegalArgumentException")
    void compare_emptySeedSet() {
        StubExecutor executor = new StubExecutor(Map.of());
        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, null);

        assertThrows(IllegalArgumentException.class,
            () -> explorer.compareCollocateProfiles(Collections.emptySet(), new pl.marcinmilkowski.word_sketch.model.ExplorationOptions(50, 0.0, 1)),
            "Empty seed set should throw IllegalArgumentException");
    }

    @Test
    @DisplayName("compare: single seed throws IllegalArgumentException (requires >= 2 seeds)")
    void compare_singleSeed() {
        StubExecutor executor = new StubExecutor(Map.of(
            "theory", List.of(wsr("empirical", 8.0), wsr("scientific", 7.5))
        ));
        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, null);

        assertThrows(IllegalArgumentException.class,
            () -> explorer.compareCollocateProfiles(Set.of("theory"), new pl.marcinmilkowski.word_sketch.model.ExplorationOptions(50, 0.0, 1)),
            "Single seed should throw IllegalArgumentException");
    }

    @Test
    @DisplayName("compare: seed with no collocates produces empty profile for that noun")
    void compare_seedWithNoCollocates() throws IOException {
        StubExecutor executor = new StubExecutor(Map.of(
            "theory", List.of(wsr("empirical", 8.0)),
            "model",  Collections.emptyList()   // no collocates
        ));

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, null);
        ComparisonResult result =
            explorer.compareCollocateProfiles(Set.of("theory", "model"), new pl.marcinmilkowski.word_sketch.model.ExplorationOptions(50, 0.0, 1));

        // empirical is specific to theory (model has no adjectives)
        List<String> specificNames = result.specific().stream()
            .map(p -> p.adjective()).toList();
        assertTrue(specificNames.contains("empirical"),
            "empirical should be specific when model has no adjectives; got: " + specificNames);
    }

    @Test
    @DisplayName("compare: null seed set throws NullPointerException (violates @NonNull contract)")
    void compare_nullSeedSet() {
        StubExecutor executor = new StubExecutor(Map.of());
        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, null);

        assertThrows(NullPointerException.class,
            () -> explorer.compareCollocateProfiles(null, new pl.marcinmilkowski.word_sketch.model.ExplorationOptions(50, 0.0, 1)),
            "Null seed set (violates @NonNull) should propagate as NullPointerException");
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

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, null);
        ComparisonResult result =
            explorer.compareCollocateProfiles(Set.of("theory", "model", "hypothesis"), new pl.marcinmilkowski.word_sketch.model.ExplorationOptions(50, 0.0, 1));

        List<String> partialNames = result.partiallyShared().stream()
            .map(p -> p.adjective()).toList();

        assertTrue(partialNames.contains("theoretical"),
            "theoretical (in 2/3 nouns) should be partially shared; got: " + partialNames);
    }

    // ── exploreByPattern ──────────────────────────────────────────────────────

    // ── exploreMultiSeed ──────────────────────────────────────────────────────

    @Test
    @DisplayName("exploreMultiSeed: returns non-null result for two seeds")
    void exploreMultiSeed_returnsNonNullResult() throws IOException {
        StubExecutor executor = new StubExecutor(Map.of(
            "theory",  List.of(wsr("empirical", 8.0), wsr("scientific", 7.0)),
            "model",   List.of(wsr("empirical", 7.5), wsr("theoretical", 6.0))
        ));

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, null);

        ExplorationResult result = explorer.exploreMultiSeed(
            Set.of("theory", "model"),
            new pl.marcinmilkowski.word_sketch.config.RelationConfig(
                "test", "test", "test", "[xpos=\"NN.*\"] [xpos=\"JJ.*\"]",
                1, 2, false, 0,
                java.util.Optional.of(pl.marcinmilkowski.word_sketch.model.RelationType.SURFACE),
                true, pl.marcinmilkowski.word_sketch.model.PosGroup.ADJ),
            new pl.marcinmilkowski.word_sketch.model.ExplorationOptions(10, 0.0, 1));

        assertNotNull(result, "Result should not be null");
    }

    @Test
    @DisplayName("exploreMultiSeed: shared collocates appear in seed collocates")
    void exploreMultiSeed_sharedCollocateInSeedMap() throws IOException {
        StubExecutor executor = new StubExecutor(Map.of(
            "theory",  List.of(wsr("empirical", 8.0), wsr("scientific", 7.0)),
            "model",   List.of(wsr("empirical", 7.5), wsr("formal", 6.0))
        ));

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, null);

        ExplorationResult result = explorer.exploreMultiSeed(
            Set.of("theory", "model"),
            new pl.marcinmilkowski.word_sketch.config.RelationConfig(
                "test", "test", "test", "[xpos=\"NN.*\"] [xpos=\"JJ.*\"]",
                1, 2, false, 0,
                java.util.Optional.of(pl.marcinmilkowski.word_sketch.model.RelationType.SURFACE),
                true, pl.marcinmilkowski.word_sketch.model.PosGroup.ADJ),
            new pl.marcinmilkowski.word_sketch.model.ExplorationOptions(10, 0.0, 2));

        assertNotNull(result.seedCollocates(), "Seed collocates should not be null");
        assertTrue(result.seedCollocates().containsKey("empirical"),
            "Shared collocate 'empirical' should appear in seed collocates map");
    }

}


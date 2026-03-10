package pl.marcinmilkowski.word_sketch.exploration;

import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.model.AdjectiveProfile;
import pl.marcinmilkowski.word_sketch.model.ComparisonResult;
import pl.marcinmilkowski.word_sketch.query.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CollocateProfileComparatorTest {

    /** Stub QueryExecutor that returns predefined collocate lists per lemma. */
    private static QueryExecutor stubExecutor(java.util.Map<String, List<QueryResults.WordSketchResult>> data) {
        return new QueryExecutor() {
            @Override
            public List<QueryResults.WordSketchResult> findCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) {
                return data.getOrDefault(lemma, List.of());
            }
            @Override public List<QueryResults.ConcordanceResult> executeQuery(String p, int m) { return List.of(); }
            @Override public List<QueryResults.CollocateResult> executeBcqlQuery(String p, int m) { return List.of(); }
            @Override public long getTotalFrequency(String lemma) { return 0; }
            @Override public List<QueryResults.WordSketchResult> executeSurfacePattern(
                    String lemma, String pattern, double minLogDice, int maxResults) { return List.of(); }
            @Override public List<QueryResults.WordSketchResult> executeDependencyPattern(
                    String lemma, String deprel, String headPosConstraint,
                    double minLogDice, int maxResults) { return List.of(); }
            @Override public void close() {}
        };
    }

    private static QueryResults.WordSketchResult wsr(String lemma, double logDice) {
        return new QueryResults.WordSketchResult(lemma, "JJ", 10L, logDice, 0.0, List.of());
    }

    @Test
    void compareCollocateProfiles_emptySeeds_returnsEmpty() throws IOException {
        CollocateProfileComparator comparator = new CollocateProfileComparator(stubExecutor(Collections.emptyMap()));
        ComparisonResult result = comparator.compareCollocateProfiles(Collections.emptySet(), 0.0, 10);

        assertNotNull(result);
        assertTrue(result.getNouns().isEmpty());
        assertTrue(result.getAllAdjectives().isEmpty());
    }

    @Test
    void compareCollocateProfiles_nullSeeds_returnsEmpty() throws IOException {
        CollocateProfileComparator comparator = new CollocateProfileComparator(stubExecutor(Collections.emptyMap()));
        ComparisonResult result = comparator.compareCollocateProfiles(null, 0.0, 10);

        assertNotNull(result);
        assertTrue(result.getNouns().isEmpty());
        assertTrue(result.getAllAdjectives().isEmpty());
    }

    @Test
    void compareCollocateProfiles_sortsByCommonalityScoreDescending() throws IOException {
        // "theory" has "important" (logDice=8) and "novel" (logDice=5)
        // "model"  has "important" (logDice=7)
        // "important" appears in both seeds → higher commonality
        // "novel" appears only in "theory" → lower commonality
        var data = new java.util.LinkedHashMap<String, List<QueryResults.WordSketchResult>>();
        data.put("theory", List.of(wsr("important", 8.0), wsr("novel", 5.0)));
        data.put("model",  List.of(wsr("important", 7.0)));

        CollocateProfileComparator comparator = new CollocateProfileComparator(stubExecutor(data));
        ComparisonResult result = comparator.compareCollocateProfiles(Set.of("theory", "model"), 0.0, 10);

        List<AdjectiveProfile> adjectives = result.getAllAdjectives();
        assertFalse(adjectives.isEmpty());

        // First profile must have the highest commonality score
        for (int i = 0; i < adjectives.size() - 1; i++) {
            assertTrue(
                adjectives.get(i).commonalityScore() >= adjectives.get(i + 1).commonalityScore(),
                "Profiles should be sorted by commonalityScore descending"
            );
        }
    }

    @Test
    void compareCollocateProfiles_identifiesAdjectivesSharedAcrossSeeds() throws IOException {
        // "important" is shared by all three seeds; "recent" is shared by two; "large" only by one
        var data = new java.util.LinkedHashMap<String, List<QueryResults.WordSketchResult>>();
        data.put("theory",     List.of(wsr("important", 9.0), wsr("recent", 6.0)));
        data.put("model",      List.of(wsr("important", 8.0), wsr("recent", 5.0)));
        data.put("hypothesis", List.of(wsr("important", 7.0), wsr("large",  4.0)));

        CollocateProfileComparator comparator = new CollocateProfileComparator(stubExecutor(data));
        ComparisonResult result = comparator.compareCollocateProfiles(
                Set.of("theory", "model", "hypothesis"), 0.0, 10);

        // "important" must be identified as fully shared (present in 3/3 nouns)
        List<AdjectiveProfile> fullyShared = result.getFullyShared();
        assertTrue(fullyShared.stream().anyMatch(p -> "important".equals(p.adjective())),
                "\"important\" should be fully shared across all three seeds");

        // "recent" must be partially shared (present in 2/3 nouns)
        List<AdjectiveProfile> partiallyShared = result.getPartiallyShared();
        assertTrue(partiallyShared.stream().anyMatch(p -> "recent".equals(p.adjective())),
                "\"recent\" should be partially shared across two seeds");

        // "large" must be specific to one noun
        List<AdjectiveProfile> specific = result.getSpecific();
        assertTrue(specific.stream().anyMatch(p -> "large".equals(p.adjective())),
                "\"large\" should be specific to a single seed");
    }
}

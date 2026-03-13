package pl.marcinmilkowski.word_sketch.exploration;

import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.model.exploration.CollocateProfile;
import pl.marcinmilkowski.word_sketch.model.exploration.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.sketch.*;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.query.StubQueryExecutor;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CollocateProfileComparatorTest {

    /** Stub QueryExecutor that returns predefined collocate lists per lemma. */
    private static QueryExecutor stubExecutor(java.util.Map<String, List<WordSketchResult>> data) {
        return new StubQueryExecutor() {
            @Override
            public List<WordSketchResult> executeCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) {
                return data.getOrDefault(lemma, List.of());
            }
        };
    }

    private static WordSketchResult wsr(String lemma, double logDice) {
        return new WordSketchResult(lemma, "JJ", 10L, logDice, 0.0, List.of());
    }

    @Test
    void compareCollocateProfiles_nullSeeds_throwsNullPointer() {
        CollocateProfileComparator comparator = new CollocateProfileComparator(stubExecutor(Collections.emptyMap()), null);
        //noinspection ConstantConditions — intentionally testing @NonNull violation
        assertThrows(NullPointerException.class,
            () -> comparator.compareCollocateProfiles(null, new ExplorationOptions(10, 0.0, 2)));
    }

    @Test
    void compareCollocateProfiles_sortsByCommonalityScoreDescending() throws IOException {
        // "theory" has "important" (logDice=8) and "novel" (logDice=5)
        // "model"  has "important" (logDice=7)
        // "important" appears in both seeds → higher commonality
        // "novel" appears only in "theory" → lower commonality
        var data = new java.util.LinkedHashMap<String, List<WordSketchResult>>();
        data.put("theory", List.of(wsr("important", 8.0), wsr("novel", 5.0)));
        data.put("model",  List.of(wsr("important", 7.0)));

        CollocateProfileComparator comparator = new CollocateProfileComparator(stubExecutor(data), null);
        ComparisonResult result = comparator.compareCollocateProfiles(Set.of("theory", "model"), new ExplorationOptions(10, 0.0, 2));

        List<CollocateProfile> adjectives = result.collocates();
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
        var data = new java.util.LinkedHashMap<String, List<WordSketchResult>>();
        data.put("theory",     List.of(wsr("important", 9.0), wsr("recent", 6.0)));
        data.put("model",      List.of(wsr("important", 8.0), wsr("recent", 5.0)));
        data.put("hypothesis", List.of(wsr("important", 7.0), wsr("large",  4.0)));

        CollocateProfileComparator comparator = new CollocateProfileComparator(stubExecutor(data), null);
        ComparisonResult result = comparator.compareCollocateProfiles(
                Set.of("theory", "model", "hypothesis"), new ExplorationOptions(10, 0.0, 2));

        // "important" must be identified as fully shared (present in 3/3 nouns)
        List<CollocateProfile> fullyShared = result.collocates().stream()
                .filter(CollocateProfile::isFullyShared).toList();
        assertTrue(fullyShared.stream().anyMatch(p -> "important".equals(p.collocate())),
                "\"important\" should be fully shared across all three seeds");

        // "recent" must be partially shared (present in 2/3 nouns)
        List<CollocateProfile> partiallyShared = result.collocates().stream()
                .filter(CollocateProfile::isPartiallyShared).toList();
        assertTrue(partiallyShared.stream().anyMatch(p -> "recent".equals(p.collocate())),
                "\"recent\" should be partially shared across two seeds");

        // "large" must be specific to one noun
        List<CollocateProfile> specific = result.collocates().stream()
                .filter(CollocateProfile::isSpecific).toList();
        assertTrue(specific.stream().anyMatch(p -> "large".equals(p.collocate())),
                "\"large\" should be specific to a single seed");
    }
}

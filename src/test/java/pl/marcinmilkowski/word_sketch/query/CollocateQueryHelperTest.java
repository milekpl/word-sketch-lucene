package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.utils.LogDiceCalculator;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CollocateQueryHelper")
class CollocateQueryHelperTest {

    /** Stub helper that returns known frequencies without a real BlackLabIndex. */
    private static CollocateQueryHelper stubHelper(Map<String, Long> frequencies) {
        return new CollocateQueryHelper(null) {
            @Override
            long getTotalFrequency(String lemma) throws IOException {
                return frequencies.getOrDefault(lemma.toLowerCase(), 0L);
            }
        };
    }

    /** Default stub with "beautiful"=50000 and "important"=80000. */
    private static CollocateQueryHelper defaultStub() {
        return new CollocateQueryHelper(null) {
            @Override
            long getTotalFrequency(String lemma) throws IOException {
                return switch (lemma.toLowerCase()) {
                    case "beautiful" -> 50000L;
                    case "important" -> 80000L;
                    default -> 0L;
                };
            }
        };
    }

    // ── extractCollocateLemma ─────────────────────────────────────────────────

    @Test
    @DisplayName("extractCollocateLemma: returns last lemma attribute from match XML")
    void extractCollocateLemma_returnsLastLemma() {
        String xml = "<w lemma=\"theory\" xpos=\"NN\"/><w lemma=\"be\" xpos=\"VBZ\"/><w lemma=\"correct\" xpos=\"JJ\"/>";
        assertEquals("correct", CollocateQueryHelper.extractCollocateLemma(xml, 0));
    }

    @Test
    @DisplayName("extractCollocateLemma: single token XML returns its lemma")
    void extractCollocateLemma_singleToken() {
        String xml = "<w lemma=\"house\" xpos=\"NN\"/>";
        assertEquals("house", CollocateQueryHelper.extractCollocateLemma(xml, 0));
    }

    @Test
    @DisplayName("extractCollocateLemma: null XML returns null")
    void extractCollocateLemma_nullXml() {
        assertNull(CollocateQueryHelper.extractCollocateLemma(null, 0));
    }

    @Test
    @DisplayName("extractCollocateLemma: empty XML returns null")
    void extractCollocateLemma_emptyXml() {
        assertNull(CollocateQueryHelper.extractCollocateLemma("", 0));
    }

    @Test
    @DisplayName("extractCollocateLemma: XML with no lemma attribute returns null")
    void extractCollocateLemma_noLemma() {
        assertNull(CollocateQueryHelper.extractCollocateLemma("<w xpos=\"NN\"/>", 0));
    }

    // ── extractCollocateLemma with positional extraction ──────────────────────

    @Test
    @DisplayName("extractCollocateLemma: collocatePos > 0 extracts by position")
    void extractCollocateLemma_byPosition() {
        String xml = "<w lemma=\"theory\" xpos=\"NN\"/><w lemma=\"be\" xpos=\"VBZ\"/><w lemma=\"correct\" xpos=\"JJ\"/>";
        // Position 1 → first lemma
        assertEquals("theory", CollocateQueryHelper.extractCollocateLemma(xml, 1));
        // Position 2 → second lemma
        assertEquals("be", CollocateQueryHelper.extractCollocateLemma(xml, 2));
        // Position 3 → third lemma
        assertEquals("correct", CollocateQueryHelper.extractCollocateLemma(xml, 3));
    }

    @Test
    @DisplayName("extractCollocateLemma: out-of-range position falls back to last lemma")
    void extractCollocateLemma_outOfRangeFallsBackToLast() {
        String xml = "<w lemma=\"house\" xpos=\"NN\"/>";
        // Position 5 is out of range; falls back to last lemma
        assertEquals("house", CollocateQueryHelper.extractCollocateLemma(xml, 5));
    }

    // ── buildAndRankCollocates ────────────────────────────────────────────────

    @Test
    @DisplayName("buildAndRankCollocates: ranks higher joint-frequency collocate first")
    void buildAndRankCollocates_ranksHigherJointFreqFirst() throws IOException {
        CollocateQueryHelper helper = defaultStub();
        Map<String, Long> freqMap = new LinkedHashMap<>();
        freqMap.put("beautiful", 100L);
        freqMap.put("important", 200L);

        List<QueryResults.WordSketchResult> results =
            helper.buildAndRankCollocates(freqMap, null, 10000L, 0.0, 10);

        assertEquals(2, results.size());
        assertEquals("important", results.get(0).lemma(),
            "Collocate with higher joint frequency should rank first");
    }

    @Test
    @DisplayName("buildAndRankCollocates: filters out results below minLogDice")
    void buildAndRankCollocates_filtersOutBelowMinLogDice() throws IOException {
        CollocateQueryHelper helper = defaultStub();
        Map<String, Long> freqMap = new LinkedHashMap<>();
        freqMap.put("beautiful", 100L);
        freqMap.put("important", 200L);

        List<QueryResults.WordSketchResult> results =
            helper.buildAndRankCollocates(freqMap, null, 10000L, 99.0, 10);

        assertTrue(results.isEmpty(), "All results should be filtered out by very high minLogDice");
    }

    @Test
    @DisplayName("buildAndRankCollocates: respects maxResults limit")
    void buildAndRankCollocates_respectsMaxResults() throws IOException {
        Map<String, Long> totalFreqs = Map.of(
            "a", 10000L, "b", 20000L, "c", 30000L, "d", 40000L, "e", 50000L);
        CollocateQueryHelper helper = stubHelper(totalFreqs);

        Map<String, Long> freqMap = new LinkedHashMap<>();
        freqMap.put("a", 50L);
        freqMap.put("b", 60L);
        freqMap.put("c", 70L);
        freqMap.put("d", 80L);
        freqMap.put("e", 90L);

        List<QueryResults.WordSketchResult> results =
            helper.buildAndRankCollocates(freqMap, null, 10000L, 0.0, 2);

        assertEquals(2, results.size(), "Should respect maxResults=2");
    }

    @Test
    @DisplayName("buildAndRankCollocates: empty freqMap returns empty list")
    void buildAndRankCollocates_emptyFreqMap_returnsEmpty() throws IOException {
        CollocateQueryHelper helper = defaultStub();

        List<QueryResults.WordSketchResult> results =
            helper.buildAndRankCollocates(Map.of(), null, 10000L, 0.0, 10);

        assertTrue(results.isEmpty(), "Empty frequency map should produce empty results");
    }

    @Test
    @DisplayName("buildAndRankCollocates: logDice values are within valid range [0, 14]")
    void buildAndRankCollocates_usesLogDiceFromCalculator() throws IOException {
        CollocateQueryHelper helper = defaultStub();
        Map<String, Long> freqMap = Map.of("beautiful", 100L);
        long headwordFreq = 10000L;

        List<QueryResults.WordSketchResult> results =
            helper.buildAndRankCollocates(freqMap, null, headwordFreq, 0.0, 10);

        assertEquals(1, results.size());
        double logDice = results.get(0).logDice();
        double expected = LogDiceCalculator.compute(100L, headwordFreq, 50000L);
        assertEquals(expected, logDice, 0.0001, "logDice should match LogDiceCalculator.compute()");
        assertTrue(logDice >= 0.0 && logDice <= 14.0,
            "logDice should be in [0, 14] range, was: " + logDice);
    }
}

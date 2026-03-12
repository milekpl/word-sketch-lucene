package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import nl.inl.blacklab.search.BlackLabIndex;
import pl.marcinmilkowski.word_sketch.model.sketch.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CI-compatible integration test for the core BlackLab query pipeline.
 *
 * <p>Exercises the full path from XML-snippet parsing (as returned by BlackLab's
 * forward index) through collocate extraction, frequency accumulation, logDice
 * scoring, and result ranking — without requiring a live BlackLab index on disk.
 *
 * <p>A concrete subclass of {@link CollocateQueryHelper} supplies controlled corpus
 * frequencies instead of querying a real index, allowing deterministic assertions
 * about end-to-end pipeline behaviour.
 */
@DisplayName("BlackLab query pipeline — CI integration")
class BlackLabIntegrationTest {

    // ── Representative BlackLab forward-index XML snippets ───────────────────

    private static final String SNIPPET_THEORY_EMPIRICAL =
            "<s><w lemma=\"the\" xpos=\"DT\"/><w lemma=\"theory\" xpos=\"NN\"/>"
            + "<w lemma=\"be\" xpos=\"VBZ\"/><w lemma=\"empirical\" xpos=\"JJ\"/></s>";

    private static final String SNIPPET_THEORY_SCIENTIFIC =
            "<s><w lemma=\"a\" xpos=\"DT\"/><w lemma=\"theory\" xpos=\"NN\"/>"
            + "<w lemma=\"seem\" xpos=\"VBZ\"/><w lemma=\"scientific\" xpos=\"JJ\"/></s>";

    private static final String SNIPPET_MODEL_EMPIRICAL =
            "<s><w lemma=\"the\" xpos=\"DT\"/><w lemma=\"model\" xpos=\"NN\"/>"
            + "<w lemma=\"be\" xpos=\"VBZ\"/><w lemma=\"empirical\" xpos=\"JJ\"/></s>";

    // ── Stub helper: controlled corpus frequencies without a real index ──────

    private static class StubCollocateHelper extends CollocateQueryHelper {

        private final Map<String, Long> corpusFreqs;

        @SuppressWarnings({"NullAway", "ConstantConditions"})
        StubCollocateHelper(Map<String, Long> corpusFreqs) {
            super((BlackLabIndex) null); // null index is safe: getTotalFrequency is overridden
            this.corpusFreqs = corpusFreqs;
        }

        @Override
        long getTotalFrequency(String lemma) {
            return corpusFreqs.getOrDefault(lemma.toLowerCase(), 0L);
        }
    }

    // ── Integration tests ────────────────────────────────────────────────────

    @Test
    @DisplayName("XML parsing feeds correct joint-frequency map into collocate ranking")
    void pipeline_xmlParsingFeedsRanking() throws IOException {
        // Phase 1 — simulate parsing BlackLab XML snippets (as produced by the forward index)
        // to extract collocate lemmas and accumulate joint co-occurrence counts.
        String[] matchXmls = {
            SNIPPET_THEORY_EMPIRICAL,
            SNIPPET_THEORY_SCIENTIFIC,
            SNIPPET_MODEL_EMPIRICAL,
            SNIPPET_THEORY_EMPIRICAL, // "empirical" appears twice in total
        };

        Map<String, Long> jointFreqMap = new HashMap<>();
        for (String xml : matchXmls) {
            String collocate = CollocateQueryHelper.extractCollocateLemma(xml, -1);
            if (collocate != null) {
                jointFreqMap.merge(collocate.toLowerCase(), 1L, Long::sum);
            }
        }

        assertEquals(3L, jointFreqMap.getOrDefault("empirical", 0L),
                "empirical should be extracted from 3 snippets (appears in snippets 1, 3, and 4)");
        assertEquals(1L, jointFreqMap.getOrDefault("scientific", 0L),
                "scientific should be extracted from 1 snippet");
        assertEquals(0L, jointFreqMap.getOrDefault("model", 0L),
                "model is a headword token, not a collocate, so should not be extracted");

        // Phase 2 — rank collocates against headword "theory" using logDice scoring
        long headwordFreq = 500L;
        Map<String, Long> corpusFreqs = Map.of(
                "theory",    headwordFreq,
                "empirical", 800L,
                "scientific", 300L,
                "model",     200L);

        StubCollocateHelper helper = new StubCollocateHelper(corpusFreqs);
        List<WordSketchResult> ranked = helper.buildAndRankCollocates(
                jointFreqMap, headwordFreq, 0.0, 10, Map.of());

        assertFalse(ranked.isEmpty(), "Should produce ranking results");
        assertTrue(ranked.stream().anyMatch(r -> "empirical".equals(r.lemma())),
                "empirical must appear in ranked results");

        // empirical has higher joint freq (2) than scientific (1) and model (1),
        // so it must rank first after logDice scoring.
        assertEquals("empirical", ranked.get(0).lemma(),
                "empirical (highest joint freq) must be ranked first");
    }

    @Test
    @DisplayName("collocates below minLogDice threshold are excluded from results")
    void pipeline_minLogDiceFilterDropsLowScorers() throws IOException {
        Map<String, Long> jointFreqMap = Map.of("rare", 1L, "common", 50L);
        long headwordFreq = 10_000L;
        Map<String, Long> corpusFreqs = Map.of(
                "rare",   1_000_000L,  // very diluted → low logDice
                "common", 5_000L);     // tightly associated → high logDice

        StubCollocateHelper helper = new StubCollocateHelper(corpusFreqs);
        List<WordSketchResult> all = helper.buildAndRankCollocates(
                jointFreqMap, headwordFreq, 0.0, 10, Map.of());
        List<WordSketchResult> filtered = helper.buildAndRankCollocates(
                jointFreqMap, headwordFreq, 5.0, 10, Map.of());

        assertTrue(all.size() >= filtered.size(),
                "Filtering should never increase result count");
        assertTrue(filtered.stream().allMatch(r -> r.logDice() >= 5.0),
                "All filtered results must meet the minLogDice threshold");
    }

    @Test
    @DisplayName("POS tag from posMap is applied to the ranked result record")
    void pipeline_posTagFromMapAppliedToResult() throws IOException {
        Map<String, Long> jointFreqMap = Map.of("empirical", 10L);
        long headwordFreq = 500L;
        Map<String, Long> corpusFreqs = Map.of("empirical", 300L);
        Map<String, String> posMap = Map.of("empirical", "JJ");

        StubCollocateHelper helper = new StubCollocateHelper(corpusFreqs);
        List<WordSketchResult> results = helper.buildAndRankCollocates(
                jointFreqMap, headwordFreq, 0.0, 10, posMap);

        assertFalse(results.isEmpty(), "Should return at least one result");
        assertEquals("JJ", results.get(0).pos(),
                "POS tag from posMap must be applied to the result record");
    }

    @Test
    @DisplayName("maxResults cap is respected even when more candidates exist")
    void pipeline_maxResultsLimitsOutput() throws IOException {
        Map<String, Long> jointFreqMap = new HashMap<>();
        Map<String, Long> corpusFreqs = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            String lemma = "word" + i;
            jointFreqMap.put(lemma, (long) (i + 1));
            corpusFreqs.put(lemma, 100L);
        }

        StubCollocateHelper helper = new StubCollocateHelper(corpusFreqs);
        List<WordSketchResult> results = helper.buildAndRankCollocates(
                jointFreqMap, 500L, 0.0, 5, Map.of());

        assertEquals(5, results.size(),
                "Result count must not exceed maxResults even with more candidates");
    }

    @Test
    @DisplayName("extractCollocateLemma falls back to last lemma when no labeled position")
    void pipeline_extractCollocateLemma_fallsBackToLastLemma() {
        String matchXml = "<w lemma=\"house\" xpos=\"NN\"/><w lemma=\"important\" xpos=\"JJ\"/>";
        String collocate = CollocateQueryHelper.extractCollocateLemma(matchXml, -1);
        assertEquals("important", collocate,
                "Should extract last lemma (adjective) from match XML when no position is given");
    }

    @Test
    @DisplayName("extractCollocateLemma uses labeled position when provided")
    void pipeline_extractCollocateLemma_usesLabeledPosition() {
        String matchXml = "<w lemma=\"house\" xpos=\"NN\"/><w lemma=\"big\" xpos=\"JJ\"/>";
        assertEquals("house", CollocateQueryHelper.extractCollocateLemma(matchXml, 1),
                "Position 1 should return first token lemma");
        assertEquals("big", CollocateQueryHelper.extractCollocateLemma(matchXml, 2),
                "Position 2 should return second token lemma");
    }

    @Test
    @DisplayName("snippet → collocate extraction pipeline handles null and empty XML gracefully")
    void pipeline_extractCollocateLemma_nullAndEmptyInputReturnNull() {
        assertNull(CollocateQueryHelper.extractCollocateLemma(null, -1),
                "null XML must return null");
        assertNull(CollocateQueryHelper.extractCollocateLemma("", -1),
                "empty XML must return null");
        assertNull(CollocateQueryHelper.extractCollocateLemma("<w xpos=\"NN\"/>", -1),
                "XML with no lemma attribute must return null");
    }
}

package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CollocateQueryHelper")
class CollocateQueryHelperTest {

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
}

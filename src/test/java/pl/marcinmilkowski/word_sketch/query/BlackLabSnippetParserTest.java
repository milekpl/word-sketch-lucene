package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.utils.CqlUtils;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BlackLabSnippetParser")
class BlackLabSnippetParserTest {

    // ── extractLemmaFromMatch ─────────────────────────────────────────────────

    @Test
    @DisplayName("extractLemmaFromMatch: returns last lemma attribute (lowercased)")
    void extractLemmaFromMatch_returnsLastLemma() {
        String xml = "<w lemma=\"theory\" xpos=\"NN\"/> <w lemma=\"be\" xpos=\"VBZ\"/> <w lemma=\"Correct\" xpos=\"JJ\"/>";
        assertEquals("correct", BlackLabSnippetParser.extractLemmaFromMatch(xml));
    }

    @Test
    @DisplayName("extractLemmaFromMatch: single token")
    void extractLemmaFromMatch_singleToken() {
        assertEquals("house", BlackLabSnippetParser.extractLemmaFromMatch("<w lemma=\"house\"/>"));
    }

    @Test
    @DisplayName("extractLemmaFromMatch: null input returns null")
    void extractLemmaFromMatch_nullReturnsNull() {
        assertNull(BlackLabSnippetParser.extractLemmaFromMatch(null));
    }

    @Test
    @DisplayName("extractLemmaFromMatch: empty input returns null")
    void extractLemmaFromMatch_emptyReturnsNull() {
        assertNull(BlackLabSnippetParser.extractLemmaFromMatch(""));
    }

    @Test
    @DisplayName("extractLemmaFromMatch: no lemma attribute returns null")
    void extractLemmaFromMatch_noLemmaReturnsNull() {
        assertNull(BlackLabSnippetParser.extractLemmaFromMatch("<w xpos=\"NN\"/>"));
    }

    // ── extractPosFromMatch ───────────────────────────────────────────────────

    @Test
    @DisplayName("extractPosFromMatch: prefers xpos over upos")
    void extractPosFromMatch_prefersXpos() {
        String xml = "<w xpos=\"NNS\" upos=\"NOUN\"/>";
        assertEquals("NNS", BlackLabSnippetParser.extractPosFromMatch(xml));
    }

    @Test
    @DisplayName("extractPosFromMatch: falls back to upos when xpos absent")
    void extractPosFromMatch_fallsBackToUpos() {
        assertEquals("NOUN", BlackLabSnippetParser.extractPosFromMatch("<w upos=\"NOUN\"/>"));
    }

    @Test
    @DisplayName("extractPosFromMatch: null returns null")
    void extractPosFromMatch_nullReturnsNull() {
        assertNull(BlackLabSnippetParser.extractPosFromMatch(null));
    }

    @Test
    @DisplayName("extractPosFromMatch: no POS attribute returns null")
    void extractPosFromMatch_noAttrReturnsNull() {
        assertNull(BlackLabSnippetParser.extractPosFromMatch("<w lemma=\"cat\"/>"));
    }

    // ── trimLeftAtSentenceBoundary ─────────────────────────────────────────────

    @Test
    @DisplayName("trimLeftAtSentenceBoundary: keeps text after last boundary")
    void trimLeft_keepsAfterLastBoundary() {
        String text = "First sentence. Second sentence. The cat sat";
        String result = BlackLabSnippetParser.trimLeftAtSentenceBoundary(text);
        assertTrue(result.startsWith("The cat sat"), "Expected text after last boundary, got: " + result);
    }

    @Test
    @DisplayName("trimLeftAtSentenceBoundary: no boundary returns trimmed text")
    void trimLeft_noBoundaryReturnsFull() {
        assertEquals("hello world", BlackLabSnippetParser.trimLeftAtSentenceBoundary("  hello world  "));
    }

    @Test
    @DisplayName("trimLeftAtSentenceBoundary: null returns empty string")
    void trimLeft_nullReturnsEmpty() {
        assertEquals("", BlackLabSnippetParser.trimLeftAtSentenceBoundary(null));
    }

    // ── trimRightAtSentenceBoundary ────────────────────────────────────────────

    @Test
    @DisplayName("trimRightAtSentenceBoundary: keeps text up to first boundary")
    void trimRight_keepsUpToFirstBoundary() {
        String text = "runs fast. Another sentence follows here.";
        String result = BlackLabSnippetParser.trimRightAtSentenceBoundary(text);
        assertTrue(result.endsWith("."), "Expected text ending at boundary, got: " + result);
        assertFalse(result.contains("Another"), "Should not include text after boundary, got: " + result);
    }

    @Test
    @DisplayName("trimRightAtSentenceBoundary: no boundary returns trimmed text")
    void trimRight_noBoundaryReturnsFull() {
        assertEquals("hello world", BlackLabSnippetParser.trimRightAtSentenceBoundary("  hello world  "));
    }

    @Test
    @DisplayName("trimRightAtSentenceBoundary: null returns empty string")
    void trimRight_nullReturnsEmpty() {
        assertEquals("", BlackLabSnippetParser.trimRightAtSentenceBoundary(null));
    }

    // ── trimLeftXmlAtSentence / trimRightXmlAtSentence ─────────────────────────

    @Test
    @DisplayName("trimLeftXmlAtSentence: keeps content after last <s> tag")
    void trimLeftXml_keepsAfterLastSTag() {
        String xml = "<s>first sentence</s><s>second sentence content";
        String result = BlackLabSnippetParser.trimLeftXmlAtSentence(xml);
        assertTrue(result.startsWith("second sentence"), "Got: " + result);
    }

    @Test
    @DisplayName("trimLeftXmlAtSentence: no <s> tag returns full input")
    void trimLeftXml_noTagReturnsFull() {
        assertEquals("<w lemma=\"foo\"/>", BlackLabSnippetParser.trimLeftXmlAtSentence("<w lemma=\"foo\"/>"));
    }

    @Test
    @DisplayName("trimRightXmlAtSentence: keeps content before first </s> tag")
    void trimRightXml_keepsBeforeFirstCloseTag() {
        String xml = "some content</s><s>more content";
        String result = BlackLabSnippetParser.trimRightXmlAtSentence(xml);
        assertEquals("some content", result);
    }

    @Test
    @DisplayName("trimRightXmlAtSentence: no </s> tag returns full input")
    void trimRightXml_noTagReturnsFull() {
        assertEquals("just text", BlackLabSnippetParser.trimRightXmlAtSentence("just text"));
    }

    // ── extractPlainTextFromXml ───────────────────────────────────────────────

    @Test
    @DisplayName("extractPlainTextFromXml: strips tags and returns plain text")
    void extractPlainText_stripsTagsAndDetokenizes() {
        String xml = "<s><w lemma=\"the\">the</w> <w lemma=\"cat\">cat</w> <w lemma=\"sit\">sits</w></s>";
        String result = BlackLabSnippetParser.extractPlainTextFromXml(xml);
        assertTrue(result.contains("the"), "Got: " + result);
        assertTrue(result.contains("cat"), "Got: " + result);
    }

    @Test
    @DisplayName("extractPlainTextFromXml: null returns empty string")
    void extractPlainText_nullReturnsEmpty() {
        assertEquals("", BlackLabSnippetParser.extractPlainTextFromXml(null));
    }

    @Test
    @DisplayName("extractPlainTextFromXml: empty returns empty string")
    void extractPlainText_emptyReturnsEmpty() {
        assertEquals("", BlackLabSnippetParser.extractPlainTextFromXml(""));
    }

    // ── detokenize ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("detokenize: removes space before punctuation")
    void detokenize_removesSpaceBeforePunct() {
        assertEquals("word,", BlackLabSnippetParser.detokenize("word ,"));
        assertEquals("word.", BlackLabSnippetParser.detokenize("word ."));
        assertEquals("word!", BlackLabSnippetParser.detokenize("word !"));
    }

    @Test
    @DisplayName("detokenize: removes space after opening bracket")
    void detokenize_removesSpaceAfterOpenBracket() {
        assertEquals("(text)", BlackLabSnippetParser.detokenize("( text)"));
    }

    @Test
    @DisplayName("detokenize: null returns null")
    void detokenize_nullReturnsNull() {
        assertNull(BlackLabSnippetParser.detokenize(null));
    }

    // ── extractCollocateFromXmlByPosition ─────────────────────────────────────

    @Test
    @DisplayName("extractCollocateFromXmlByPosition: extracts token at given position")
    void extractCollocateByPosition_correctPosition() {
        String xml = "<w lemma=\"theory\"/> <w lemma=\"be\"/> <w lemma=\"correct\"/>";
        assertEquals("theory", BlackLabSnippetParser.extractCollocateFromXmlByPosition(xml, 1));
        assertEquals("be", BlackLabSnippetParser.extractCollocateFromXmlByPosition(xml, 2));
        assertEquals("correct", BlackLabSnippetParser.extractCollocateFromXmlByPosition(xml, 3));
    }

    @Test
    @DisplayName("extractCollocateFromXmlByPosition: position beyond count returns null")
    void extractCollocateByPosition_outOfBoundsReturnsNull() {
        assertNull(BlackLabSnippetParser.extractCollocateFromXmlByPosition("<w lemma=\"cat\"/>", 5));
    }

    @Test
    @DisplayName("extractCollocateFromXmlByPosition: position zero returns null")
    void extractCollocateByPosition_zeroReturnsNull() {
        assertNull(BlackLabSnippetParser.extractCollocateFromXmlByPosition("<w lemma=\"cat\"/>", 0));
    }

    // ── extractHeadword ───────────────────────────────────────────────────────

    @Test
    @DisplayName("extractHeadword: extracts lemma from double-quoted BCQL pattern")
    void extractHeadword_doubleQuotes() {
        assertEquals("theory", CqlUtils.extractHeadword("[lemma=\"theory\"] [xpos=\"JJ.*\"]"));
    }

    @Test
    @DisplayName("extractHeadword: extracts lemma from single-quoted pattern")
    void extractHeadword_singleQuotes() {
        assertEquals("model", CqlUtils.extractHeadword("[lemma='model']"));
    }

    @Test
    @DisplayName("extractHeadword: no lemma attribute returns null")
    void extractHeadword_noLemmaReturnsNull() {
        assertNull(CqlUtils.extractHeadword("[xpos=\"NN.*\"]"));
    }

    // ── findLabelTokenIndex ─────────────────────────────────────────────────────

    @Test
    @DisplayName("findLabelTokenIndex: finds 1-based position of labeled token")
    void findLabelTokenIndex_correctPosition() {
        String pattern = "1:[xpos=\"NN.*\"] [lemma=\"be\"] 2:[xpos=\"JJ.*\"]";
        assertEquals(1, CqlUtils.findLabelTokenIndex(pattern, 1));
        assertEquals(3, CqlUtils.findLabelTokenIndex(pattern, 2));
    }

    @Test
    @DisplayName("findLabelTokenIndex: missing label returns -1")
    void findLabelTokenIndex_missingLabelReturnsNegOne() {
        assertEquals(-1, CqlUtils.findLabelTokenIndex("[xpos=\"NN.*\"]", 2));
    }

    @Test
    @DisplayName("findLabelTokenIndex: null pattern returns -1")
    void findLabelTokenIndex_nullPatternReturnsNegOne() {
        assertEquals(-1, CqlUtils.findLabelTokenIndex(null, 1));
    }

    // ── extractCollocateFromXmlByPosition (positional) ───────────────────────

    @Test
    @DisplayName("extractCollocateFromXmlByPosition: extracts token at exact position")
    void extractLemmaAt_exactPosition() {
        String xml = "<w lemma=\"the\"/> <w lemma=\"quick\"/> <w lemma=\"fox\"/>";
        assertEquals("the", BlackLabSnippetParser.extractCollocateFromXmlByPosition(xml, 1));
        assertEquals("quick", BlackLabSnippetParser.extractCollocateFromXmlByPosition(xml, 2));
        assertEquals("fox", BlackLabSnippetParser.extractCollocateFromXmlByPosition(xml, 3));
    }

    @Test
    @DisplayName("extractCollocateFromXmlByPosition: position beyond count returns null")
    void extractLemmaAt_beyondCountReturnsNull() {
        String xml = "<w lemma=\"cat\"/> <w lemma=\"dog\"/>";
        assertNull(BlackLabSnippetParser.extractCollocateFromXmlByPosition(xml, 10));
    }

    @Test
    @DisplayName("extractCollocateFromXmlByPosition: null input returns null")
    void extractLemmaAt_nullReturnsNull() {
        assertNull(BlackLabSnippetParser.extractCollocateFromXmlByPosition(null, 1));
    }

    // ── trimToSentence integration ────────────────────────────────────────────

    @Test
    @DisplayName("trimToSentence: assembles trimmed sentence from parts")
    void trimToSentence_assemblesParts() {
        String left  = "Something happened. The scientist";
        String match = "proposed";
        String right = "a new theory. Later it was tested.";
        String result = BlackLabSnippetParser.trimToSentence(left, match, right);
        assertTrue(result.contains("scientist"), "Should include post-boundary left, got: " + result);
        assertTrue(result.contains("proposed"),  "Should include match, got: " + result);
        assertTrue(result.contains("theory"),    "Should include right up to boundary, got: " + result);
        assertFalse(result.contains("Later"),    "Should not include text after right boundary, got: " + result);
    }

    @Test
    @DisplayName("trimToSentence: empty contexts return just the match")
    void trimToSentence_emptyContextsReturnMatch() {
        assertEquals("proposed", BlackLabSnippetParser.trimToSentence("", "proposed", ""));
    }
}

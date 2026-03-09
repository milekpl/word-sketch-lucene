package pl.marcinmilkowski.word_sketch.tagging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SimpleTagger.
 */
class SimpleTaggerTest {

    private SimpleTagger tagger;

    @BeforeEach
    void setUp() {
        tagger = SimpleTagger.create();
    }

    @Test
    void testTagDeterminer() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("The dog");
        assertEquals(2, tokens.size());
        assertEquals("DT", tokens.get(0).getTag());
    }

    @Test
    void testTagNoun() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("dog");
        assertEquals(1, tokens.size());
        assertEquals("NN", tokens.get(0).getTag());
    }

    @Test
    void testTagVerb() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("runs");
        assertEquals(1, tokens.size());
        assertEquals("VBZ", tokens.get(0).getTag());
    }

    @Test
    void testTagAdjective() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("big");
        assertEquals(1, tokens.size());
        assertEquals("JJ", tokens.get(0).getTag());
    }

    @Test
    void testTagAdverb() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("quickly");
        assertEquals(1, tokens.size());
        assertEquals("RB", tokens.get(0).getTag());
    }

    @Test
    void testTagAdverbEnding() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("slowly");
        assertEquals(1, tokens.size());
        assertEquals("RB", tokens.get(0).getTag());
    }

    @Test
    void testTagPreposition() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("in");
        assertEquals(1, tokens.size());
        assertEquals("IN", tokens.get(0).getTag());
    }

    @Test
    void testTagConjunction() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("and");
        assertEquals(1, tokens.size());
        assertEquals("CC", tokens.get(0).getTag());
    }

    @Test
    void testTagPronoun() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("he");
        assertEquals(1, tokens.size());
        assertEquals("PP", tokens.get(0).getTag());
    }

    @Test
    void testTagAuxiliaryVerb() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("is");
        assertEquals(1, tokens.size());
        assertEquals("VBZ", tokens.get(0).getTag());
    }

    @Test
    void testTagPastTense() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("walked");
        assertEquals(1, tokens.size());
        assertEquals("VBD", tokens.get(0).getTag());
    }

    @Test
    void testTagPresentParticiple() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("walking");
        assertEquals(1, tokens.size());
        assertEquals("VBG", tokens.get(0).getTag());
    }

    @Test
    void testTagNounSuffix() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("happiness");
        assertEquals(1, tokens.size());
        assertEquals("NN", tokens.get(0).getTag());
    }

    @Test
    void testTagAdjectiveSuffix() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("beautiful");
        assertEquals(1, tokens.size());
        assertEquals("JJ", tokens.get(0).getTag());
    }

    @Test
    void testTagVerbSuffix() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("modernize");
        assertEquals(1, tokens.size());
        assertEquals("VB", tokens.get(0).getTag());
    }

    @Test
    void testTagNumber() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("123");
        assertEquals(1, tokens.size());
        assertEquals("CD", tokens.get(0).getTag());
    }

    @Test
    void testTagProperNoun() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("London");
        assertEquals(1, tokens.size());
        assertEquals("NNP", tokens.get(0).getTag());
    }

    @Test
    void testTagSimpleSentence() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("The big dog runs quickly");

        // "The big dog runs quickly" = 5 words
        assertEquals(5, tokens.size());
        assertEquals("DT", tokens.get(0).getTag()); // The
        assertEquals("JJ", tokens.get(1).getTag()); // big
        assertEquals("NN", tokens.get(2).getTag()); // dog
        assertTrue(tokens.get(3).getTag().startsWith("VB")); // runs
        assertEquals("RB", tokens.get(4).getTag()); // quickly
    }

    @Test
    void testPosGroup() {
        PosTagger.TaggedToken noun = new PosTagger.TaggedToken("dog", "dog", "NN", 0);
        PosTagger.TaggedToken verb = new PosTagger.TaggedToken("runs", "run", "VBZ", 1);
        PosTagger.TaggedToken adj = new PosTagger.TaggedToken("big", "big", "JJ", 2);
        PosTagger.TaggedToken adv = new PosTagger.TaggedToken("quickly", "quick", "RB", 3);

        assertEquals("noun", noun.getPosGroup());
        assertEquals("verb", verb.getPosGroup());
        assertEquals("adj", adj.getPosGroup());
        assertEquals("adv", adv.getPosGroup());
    }

    @Test
    void testGetName() {
        assertTrue(tagger.getName().contains("Simple Tagger"));
    }

    @Test
    void testGetTagset() {
        assertTrue(tagger.getTagset().contains("Penn Treebank"));
    }

    @Test
    void testMultipleSentences() throws IOException {
        List<String> sentences = Arrays.asList(
            "The cat sleeps.",
            "The dog runs."
        );

        List<List<PosTagger.TaggedToken>> results = tagger.tagSentences(sentences);

        assertEquals(2, results.size());
        assertTrue(results.get(0).size() > 0);
        assertTrue(results.get(1).size() > 0);
    }

    @Test
    void testTokenPosition() throws IOException {
        List<PosTagger.TaggedToken> tokens = tagger.tagSentence("The big dog");

        assertEquals(0, tokens.get(0).getPosition());
        assertEquals(1, tokens.get(1).getPosition());
        assertEquals(2, tokens.get(2).getPosition());
    }
}

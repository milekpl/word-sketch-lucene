package pl.marcinmilkowski.word_sketch.query;

import nl.inl.blacklab.queryParser.corpusql.CorpusQueryLanguageParser;
import nl.inl.blacklab.search.textpattern.TextPattern;
import nl.inl.blacklab.exceptions.InvalidQuery;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestBCQLParser {

    @Test
    public void testSimplePosPattern() {
        assertDoesNotThrow(() -> {
            TextPattern pattern = CorpusQueryLanguageParser.parse("[pos=\"NN\"]", "lemma");
            assertNotNull(pattern);
        });
    }

    @Test
    public void testPosAndLemmaSequence() {
        assertDoesNotThrow(() -> {
            TextPattern pattern = CorpusQueryLanguageParser.parse("[pos=\"NN\"] [lemma=\"test\"]", "lemma");
            assertNotNull(pattern);
        });
    }

    @Test
    public void testQuotedWordFollowedByPos() {
        assertDoesNotThrow(() -> {
            TextPattern pattern = CorpusQueryLanguageParser.parse("\"test\" [pos=\"NN\"]", "lemma");
            assertNotNull(pattern);
        });
    }

    @Test
    public void testInvalidPatternThrowsInvalidQuery() {
        assertThrows(InvalidQuery.class, () ->
            CorpusQueryLanguageParser.parse("[[[invalid", "lemma")
        );
    }
}

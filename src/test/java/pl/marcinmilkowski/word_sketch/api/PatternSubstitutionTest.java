package pl.marcinmilkowski.word_sketch.api;

import org.junit.jupiter.api.Test;

import pl.marcinmilkowski.word_sketch.utils.CqlUtils;

import static org.junit.jupiter.api.Assertions.*;

class PatternSubstitutionTest {

    @Test
    void testSubstituteCollocateAtPosition1() {
        String pattern = "[xpos=\"NN.*\"] [xpos=\"VBZ\"]";
        String result = PatternSubstitution.substituteCollocate(pattern, "dog", 1);
        assertTrue(result.contains("lemma=\"dog\""), "Should contain lemma=dog");
        assertTrue(result.contains("xpos=\"NN.*\""), "Should preserve xpos constraint");
    }

    @Test
    void testSubstituteCollocateAtPosition2() {
        String pattern = "[xpos=\"NN.*\"] [xpos=\"VBZ\"]";
        String result = PatternSubstitution.substituteCollocate(pattern, "run", 2);
        assertTrue(result.contains("lemma=\"run\""), "Should contain lemma=run");
        assertTrue(result.contains("xpos=\"VBZ\""), "Should preserve xpos constraint at position 2");
    }

    @Test
    void testSubstituteCollocateWithSpecialChars() {
        String pattern = "[xpos=\"NN.*\"] [xpos=\"JJ\"]";
        String result = PatternSubstitution.substituteCollocate(pattern, "well-known", 2);
        assertNotNull(result);
        assertTrue(result.contains("lemma=\"well-known\""), "Should contain the collocate with special chars");
    }

    @Test
    void testSubstituteCollocateNullPattern() {
        String result = PatternSubstitution.substituteCollocate(null, "dog", 1);
        assertNull(result, "Null pattern should return null");
    }

    @Test
    void testSubstituteCollocateNullCollocate() {
        String pattern = "[xpos=\"NN.*\"]";
        String result = PatternSubstitution.substituteCollocate(pattern, null, 1);
        assertEquals(pattern, result, "Null collocate should return original pattern");
    }

    @Test
    void testSubstituteCollocateZeroPosition() {
        String pattern = "[xpos=\"NN.*\"]";
        String result = PatternSubstitution.substituteCollocate(pattern, "dog", 0);
        assertEquals(pattern, result, "Position < 1 should return original pattern");
    }

    @Test
    void testSubstituteCollocatePositionBeyondPattern() {
        String pattern = "[xpos=\"NN.*\"]";
        String result = PatternSubstitution.substituteCollocate(pattern, "dog", 5);
        assertEquals(pattern, result, "Position beyond pattern length should return original pattern");
    }

    @Test
    void testExtractXposFromConstraintWithXpos() {
        String constraint = "[xpos=\"NN.*\"]";
        String xpos = CqlUtils.extractConstraintAttribute(constraint, "xpos");
        assertNotNull(xpos);
        assertTrue(xpos.contains("xpos=\"NN.*\""), "Should extract xpos attribute");
    }

    @Test
    void testExtractXposFromConstraintWithTag() {
        String constraint = "[tag=\"VBZ\"]";
        String tag = CqlUtils.extractConstraintAttribute(constraint, "tag");
        assertNotNull(tag);
        assertTrue(tag.contains("tag=\"VBZ\""), "Should extract tag attribute");
    }

    @Test
    void testExtractXposFromConstraintNull() {
        assertNull(CqlUtils.extractConstraintAttribute(null, "xpos"));
    }

    @Test
    void testEscapeForRegex() {
        assertEquals("dog", CqlUtils.escapeForRegex("dog"));
        assertEquals("well\\\\known", CqlUtils.escapeForRegex("well\\known"));
        assertEquals("say\\\"hello\\\"", CqlUtils.escapeForRegex("say\"hello\""));
    }

    @Test
    void testEscapeForRegexNull() {
        assertEquals("", CqlUtils.escapeForRegex(null));
    }
}

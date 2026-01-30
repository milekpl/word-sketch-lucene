package pl.marcinmilkowski.word_sketch.grammar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CQLParser.
 */
class CQLParserTest {

    private final CQLParser parser = new CQLParser();

    @Test
    void testSimpleLabeledPosition() {
        String cql = "1:\"N.*\"";
        CQLPattern pattern = parser.parse(cql);

        List<CQLPattern.PatternElement> elements = pattern.getElements();
        assertEquals(1, elements.size());

        CQLPattern.PatternElement element = elements.get(0);
        assertEquals(1, element.getPosition());
        assertEquals("N.*", element.getTarget());
    }

    @Test
    void testUnlabeledPosition() {
        String cql = "\"VB.*\"";
        CQLPattern pattern = parser.parse(cql);

        List<CQLPattern.PatternElement> elements = pattern.getElements();
        assertEquals(1, elements.size());

        CQLPattern.PatternElement element = elements.get(0);
        assertEquals(-1, element.getPosition());
        assertEquals("VB.*", element.getTarget());
    }

    @Test
    void testSequence() {
        String cql = "1:\"JJ.*\" 2:\"N.*\"";
        CQLPattern pattern = parser.parse(cql);

        List<CQLPattern.PatternElement> elements = pattern.getElements();
        assertEquals(2, elements.size());

        assertEquals(1, elements.get(0).getPosition());
        assertEquals("JJ.*", elements.get(0).getTarget());

        assertEquals(2, elements.get(1).getPosition());
        assertEquals("N.*", elements.get(1).getTarget());
    }

    @Test
    void testConstraint() {
        String cql = "[tag=\"JJ\"]";
        CQLPattern pattern = parser.parse(cql);

        List<CQLPattern.PatternElement> elements = pattern.getElements();
        assertEquals(1, elements.size());

        CQLPattern.Constraint constraint = elements.get(0).getConstraint();
        assertNotNull(constraint);
        assertEquals("tag", constraint.getField());
        assertEquals("JJ", constraint.getPattern());
        assertFalse(constraint.isNegated());
    }

    @Test
    void testNegatedConstraint() {
        String cql = "[!tag=\"N.*\"]";
        CQLPattern pattern = parser.parse(cql);

        List<CQLPattern.PatternElement> elements = pattern.getElements();
        CQLPattern.Constraint constraint = elements.get(0).getConstraint();
        assertNotNull(constraint);
        assertTrue(constraint.isNegated());
        assertEquals("tag", constraint.getField());
    }

    @Test
    void testOrConstraint() {
        String cql = "[tag=\"JJ\"|tag=\"RB\"]";
        CQLPattern pattern = parser.parse(cql);

        List<CQLPattern.PatternElement> elements = pattern.getElements();
        CQLPattern.Constraint constraint = elements.get(0).getConstraint();

        assertTrue(constraint.isOr());
        assertEquals(2, constraint.getOrConstraints().size());
    }

    @Test
    void testMacroExpansion() {
        String cql = "NOUN";
        String expanded = CQLParser.expandMacros(cql, java.util.Map.of(
            "NOUN", "\"N.*[^Z]\"",
            "VERB", "\"V.*\""
        ));

        assertEquals("\"N.*[^Z]\"", expanded);
    }

    @Test
    void testEmptyPattern() {
        // Empty pattern should return empty result, not throw
        CQLPattern pattern = parser.parse("");
        assertTrue(pattern.getElements().isEmpty());
    }

    @Test
    void testWordConstraint() {
        String cql = "[word=\"the\"]";
        CQLPattern pattern = parser.parse(cql);

        CQLPattern.Constraint constraint = pattern.getElements().get(0).getConstraint();
        assertEquals("word", constraint.getField());
        assertEquals("the", constraint.getPattern());
    }

    @Test
    void testWhitespaceHandling() {
        String cql = "  1:NOUN  2:VERB  ";
        CQLPattern pattern = parser.parse(cql);

        assertEquals(2, pattern.getElements().size());
    }

    @Test
    void testConstraintWithDistance() {
        // Test: [tag="JJ.*"] ~ {0,3}
        String cql = "[tag=\"JJ.*\"] ~ {0,3}";
        CQLPattern pattern = parser.parse(cql);

        assertEquals(1, pattern.getElements().size());
        CQLPattern.PatternElement elem = pattern.getElements().get(0);
        assertEquals("tag", elem.getConstraint().getField());
        assertEquals("JJ.*", elem.getConstraint().getPattern());
        assertEquals(0, elem.getMinDistance());
        assertEquals(3, elem.getMaxDistance());
    }

    @Test
    void testConstraintWithDistanceNoSpaces() {
        // Test: [tag="VB.*"]~{0,5}
        String cql = "[tag=\"VB.*\"]~{0,5}";
        CQLPattern pattern = parser.parse(cql);

        assertEquals(1, pattern.getElements().size());
        CQLPattern.PatternElement elem = pattern.getElements().get(0);
        assertEquals(0, elem.getMinDistance());
        assertEquals(5, elem.getMaxDistance());
    }

    @Test
    void testNegativeDistance() {
        // Test: [tag="NN.*"]~{-5,0} (elements 1-5 words before the headword)
        String cql = "[tag=\"NN.*\"]~{-5,0}";
        CQLPattern pattern = parser.parse(cql);

        assertEquals(1, pattern.getElements().size());
        CQLPattern.PatternElement elem = pattern.getElements().get(0);
        assertEquals(-5, elem.getMinDistance());
        assertEquals(0, elem.getMaxDistance());
    }

    @Test
    void testVerbPatternFromSketchGrammar() {
        // Test: [tag="V.*"] - from VERBODE_Macro in sketchgrammar.wsdef.m4
        String cql = "[tag=\"V.*\"]";
        CQLPattern pattern = parser.parse(cql);

        assertEquals(1, pattern.getElements().size());
        assertEquals("V.*", pattern.getElements().get(0).getConstraint().getPattern());
    }

    @Test
    void testNounPatternFromSketchGrammar() {
        // Test: [tag="N.*[^Z]"] - from NOUN macro in sketchgrammar.wsdef.m4
        String cql = "[tag=\"N.*[^Z]\"]";
        CQLPattern pattern = parser.parse(cql);

        assertEquals(1, pattern.getElements().size());
        assertEquals("N.*[^Z]", pattern.getElements().get(0).getConstraint().getPattern());
    }

    @Test
    void testModifierPattern() {
        // Test: [tag="JJ.*"|tag="RB.*"] - from MODIFIER macro
        String cql = "[tag=\"JJ.*\"|tag=\"RB.*\"]";
        CQLPattern pattern = parser.parse(cql);

        CQLPattern.Constraint c = pattern.getElements().get(0).getConstraint();
        assertEquals("JJ.*", c.getPattern());
        assertEquals(2, c.getOrConstraints().size());
    }

    @Test
    void testNegatedNounConstraint() {
        // Test: [tag!="N.*"] - from NOT_NOUN macro
        String cql = "[tag!=\"N.*\"]";
        CQLPattern pattern = parser.parse(cql);

        CQLPattern.Constraint c = pattern.getElements().get(0).getConstraint();
        assertTrue(c.isNegated());
        assertEquals("N.*", c.getPattern());
    }

    @Test
    void testComplexNegatedConstraint() {
        // Test: [tag!="N.*" & tag!="CC" & tag!="JJ.*"]
        String cql = "[tag!=\"N.*\" & tag!=\"CC\" & tag!=\"JJ.*\"]";
        CQLPattern pattern = parser.parse(cql);

        CQLPattern.Constraint c = pattern.getElements().get(0).getConstraint();
        assertTrue(c.isNegated());
        assertNotNull(c.getAndConstraints());
        assertEquals(3, c.getAndConstraints().size());
    }

    @Test
    void testDeterminerPattern() {
        // Test: [tag="DT"|tag="PPZ"] - from DETERMINER macro
        String cql = "[tag=\"DT\"|tag=\"PPZ\"]";
        CQLPattern pattern = parser.parse(cql);

        CQLPattern.Constraint c = pattern.getElements().get(0).getConstraint();
        assertEquals("DT", c.getPattern());
        assertEquals(2, c.getOrConstraints().size());
    }

    @Test
    void testWhoWhichThatPattern() {
        // Test: [tag="WP"|tag="IN/that"] - from WHO_WHICH_THAT macro
        String cql = "[tag=\"WP\"|tag=\"IN/that\"]";
        CQLPattern pattern = parser.parse(cql);

        assertEquals(1, pattern.getElements().size());
    }

    @Test
    void testVerbBePattern() {
        // Test: [tag="VB.*"] - from VERB_BE macro
        String cql = "[tag=\"VB.*\"]";
        CQLPattern pattern = parser.parse(cql);

        assertEquals("VB.*", pattern.getElements().get(0).getConstraint().getPattern());
    }

    @Test
    void testVerbHavePattern() {
        // Test: [tag="VH.*"] - from VERB_HAVE macro
        String cql = "[tag=\"VH.*\"]";
        CQLPattern pattern = parser.parse(cql);

        assertEquals("VH.*", pattern.getElements().get(0).getConstraint().getPattern());
    }

    @Test
    void testAdjectivePattern() {
        // Test: [tag="JJ.*"] - from ADJECTIVE macro
        String cql = "[tag=\"JJ.*\"]";
        CQLPattern pattern = parser.parse(cql);

        assertEquals("JJ.*", pattern.getElements().get(0).getConstraint().getPattern());
    }

    @Test
    void testAdverbPattern() {
        // Test: [tag="RB.*"] - from ADVERB macro
        String cql = "[tag=\"RB.*\"]";
        CQLPattern pattern = parser.parse(cql);

        assertEquals("RB.*", pattern.getElements().get(0).getConstraint().getPattern());
    }

    @Test
    void testFullVerbObjectPattern() {
        // Simulates: 1:VERB ADVERB{0,2} DETERMINER{0,1} "CD"{0,2} MODIFIER{0,3} NOUN{0,2} 2:NOUN NOT_NOUN
        String cql = "[tag=\"V.*\"] ~ {0,5} [tag=\"N.*\"]";
        CQLPattern pattern = parser.parse(cql);

        assertEquals(2, pattern.getElements().size());
        assertEquals("V.*", pattern.getElements().get(0).getConstraint().getPattern());
        assertEquals("N.*", pattern.getElements().get(1).getConstraint().getPattern());
    }

    @Test
    void testConstraintWithRegexAlternatives() {
        // Test regex pattern with pipes inside quoted value (value-level OR)
        String cql = "[word=\"be|remain|seem\"]";
        CQLPattern pattern = parser.parse(cql);

        List<CQLPattern.PatternElement> elements = pattern.getElements();
        assertEquals(1, elements.size());

        CQLPattern.PatternElement element = elements.get(0);
        CQLPattern.Constraint constraint = element.getConstraint();

        assertEquals("word", constraint.getField());
        assertEquals("be|remain|seem", constraint.getPattern());
        assertFalse(constraint.isNegated());
        assertFalse(constraint.isOr()); // No OR constraints - it's a regex pattern
    }

    @Test
    void testSnowballLinkingVerbPattern() {
        // Test the actual snowball linking verb pattern with many alternatives
        String cql = "[word=\"be|remain|seem|appear|feel|get|become|look|smell|taste\"] [tag=\"JJ.*\"]";
        CQLPattern pattern = parser.parse(cql);

        List<CQLPattern.PatternElement> elements = pattern.getElements();
        assertEquals(2, elements.size());

        // First element: linking verbs with regex OR
        CQLPattern.Constraint wordConstraint = elements.get(0).getConstraint();
        assertEquals("word", wordConstraint.getField());
        assertEquals("be|remain|seem|appear|feel|get|become|look|smell|taste", 
                     wordConstraint.getPattern());
        assertFalse(wordConstraint.isNegated());
        assertFalse(wordConstraint.isOr());

        // Second element: adjective tag pattern
        CQLPattern.Constraint tagConstraint = elements.get(1).getConstraint();
        assertEquals("tag", tagConstraint.getField());
        assertEquals("JJ.*", tagConstraint.getPattern());
        assertFalse(tagConstraint.isNegated());
        assertFalse(tagConstraint.isOr());
    }

    @Test
    void testFieldLevelOrStillWorks() {
        // Ensure existing field-level OR syntax still works correctly
        String cql = "[tag=\"JJ\"|tag=\"RB\"]";
        CQLPattern pattern = parser.parse(cql);

        List<CQLPattern.PatternElement> elements = pattern.getElements();
        assertEquals(1, elements.size());

        CQLPattern.Constraint constraint = elements.get(0).getConstraint();
        assertEquals("tag", constraint.getField());
        assertEquals("JJ", constraint.getPattern());
        assertFalse(constraint.isNegated());
        assertTrue(constraint.isOr());
        assertEquals(2, constraint.getOrConstraints().size());

        // Verify OR constraints
        assertEquals("tag", constraint.getOrConstraints().get(0).getField());
        assertEquals("JJ", constraint.getOrConstraints().get(0).getPattern());
        assertEquals("tag", constraint.getOrConstraints().get(1).getField());
        assertEquals("RB", constraint.getOrConstraints().get(1).getPattern());
    }

    @Test
    void testMixedRegexAndFieldOr() {
        // Test complex case: field-level OR where one value contains pipes
        String cql = "[word=\"be|is\"|word=\"seem|appear\"]";
        CQLPattern pattern = parser.parse(cql);

        List<CQLPattern.PatternElement> elements = pattern.getElements();
        assertEquals(1, elements.size());

        CQLPattern.Constraint constraint = elements.get(0).getConstraint();
        assertEquals("word", constraint.getField());
        assertEquals("be|is", constraint.getPattern()); // First value with pipe preserved
        assertTrue(constraint.isOr());
        assertEquals(2, constraint.getOrConstraints().size());

        // Second OR constraint should also preserve pipes
        assertEquals("word", constraint.getOrConstraints().get(1).getField());
        assertEquals("seem|appear", constraint.getOrConstraints().get(1).getPattern());
    }

    @Test
    void testRegexWithoutQuotes() {
        // Test simple word pattern without quotes (should still work)
        String cql = "[word=big]";
        CQLPattern pattern = parser.parse(cql);

        List<CQLPattern.PatternElement> elements = pattern.getElements();
        assertEquals(1, elements.size());

        CQLPattern.Constraint constraint = elements.get(0).getConstraint();
        assertEquals("word", constraint.getField());
        assertEquals("big", constraint.getPattern());
        assertFalse(constraint.isOr());
    }
}

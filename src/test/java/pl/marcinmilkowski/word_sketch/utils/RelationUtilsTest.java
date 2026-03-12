package pl.marcinmilkowski.word_sketch.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RelationUtils#resolveRelationAlias(String)}.
 */
class RelationUtilsTest {

    @Test
    void adjPredicateAlias_resolvesToCanonical() {
        assertEquals("noun_adj_predicates", RelationUtils.resolveRelationAlias("adj_predicate"));
    }

    @Test
    void predicateAlias_resolvesToCanonical() {
        assertEquals("noun_adj_predicates", RelationUtils.resolveRelationAlias("predicate"));
    }

    @Test
    void adjModifierAlias_resolvesToCanonical() {
        assertEquals("noun_modifiers", RelationUtils.resolveRelationAlias("adj_modifier"));
    }

    @Test
    void modifierAlias_resolvesToCanonical() {
        assertEquals("noun_modifiers", RelationUtils.resolveRelationAlias("modifier"));
    }

    @Test
    void subjectOfAlias_resolvesToCanonical() {
        assertEquals("noun_verbs", RelationUtils.resolveRelationAlias("subject_of"));
    }

    @Test
    void subjectAlias_resolvesToCanonical() {
        assertEquals("noun_verbs", RelationUtils.resolveRelationAlias("subject"));
    }

    @Test
    void objectOfAlias_resolvesToCanonical() {
        assertEquals("verb_nouns", RelationUtils.resolveRelationAlias("object_of"));
    }

    @Test
    void objectAlias_resolvesToCanonical() {
        assertEquals("verb_nouns", RelationUtils.resolveRelationAlias("object"));
    }

    @Test
    void canonicalId_passesThroughUnchanged() {
        assertEquals("noun_adj_predicates", RelationUtils.resolveRelationAlias("noun_adj_predicates"));
        assertEquals("noun_modifiers", RelationUtils.resolveRelationAlias("noun_modifiers"));
        assertEquals("noun_verbs", RelationUtils.resolveRelationAlias("noun_verbs"));
        assertEquals("verb_nouns", RelationUtils.resolveRelationAlias("verb_nouns"));
    }

    @Test
    void resolution_isCaseInsensitive() {
        assertEquals("noun_adj_predicates", RelationUtils.resolveRelationAlias("ADJ_PREDICATE"));
        assertEquals("noun_adj_predicates", RelationUtils.resolveRelationAlias("Adj_Predicate"));
        assertEquals("noun_modifiers", RelationUtils.resolveRelationAlias("MODIFIER"));
        assertEquals("noun_verbs", RelationUtils.resolveRelationAlias("Subject_Of"));
        assertEquals("verb_nouns", RelationUtils.resolveRelationAlias("OBJECT_OF"));
    }

    @Test
    void unknownInput_returnsLowercasedInput() {
        assertEquals("unknown_relation", RelationUtils.resolveRelationAlias("unknown_relation"));
        assertEquals("blah", RelationUtils.resolveRelationAlias("BLAH"));
    }

    @Test
    void nullInput_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> RelationUtils.resolveRelationAlias(null));
    }
}

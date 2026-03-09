package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for grammar config with new format.
 */
class HybridAlgorithmTest {

    @Test
    @DisplayName("Grammar config loads with new format")
    void testGrammarConfigLoads() {
        GrammarConfigLoader grammarConfig = GrammarConfigLoader.createDefaultEnglish();
        assertNotNull(grammarConfig, "Grammar config should load");
        assertFalse(grammarConfig.getRelations().isEmpty(), "Should have relations");

        // Check noun_adj_predicates has full pattern with copula
        var adjPredRel = grammarConfig.getRelation("noun_adj_predicates").orElse(null);
        assertNotNull(adjPredRel, "noun_adj_predicates should exist");
        String pattern = adjPredRel.pattern();
        assertNotNull(pattern, "Should have pattern");
        assertTrue(pattern.contains("lemma="), "Pattern should contain lemma constraint for copula");
    }

    @Test
    @DisplayName("NOUN_MODIFIERS: Pattern should match adjectives")
    void testNounModifiersRelation() {
        GrammarConfigLoader grammarConfig = GrammarConfigLoader.createDefaultEnglish();

        var rel = grammarConfig.getRelation("noun_modifiers").orElse(null);
        assertNotNull(rel, "noun_modifiers relation should exist");

        // Check pattern contains adjective tag
        String cqlPattern = rel.pattern();
        assertNotNull(cqlPattern, "Should have pattern");
        assertTrue(cqlPattern.toLowerCase().contains("jj"), "Pattern should match adjectives");
    }

    @Test
    @DisplayName("NOUN_ADJ_PREDICATES: Pattern should include copula lemmas")
    void testAdjPredicatesRelation() {
        GrammarConfigLoader grammarConfig = GrammarConfigLoader.createDefaultEnglish();

        var rel = grammarConfig.getRelation("noun_adj_predicates").orElse(null);
        assertNotNull(rel, "noun_adj_predicates relation should exist");

        // Pattern should include copula lemmas like "be", "appear", "seem"
        String pattern = rel.pattern();
        assertNotNull(pattern, "Should have pattern");
        assertTrue(pattern.contains("lemma="), "Pattern should have lemma constraint");
        assertTrue(pattern.contains("be|appear|seem"), "Pattern should include copula lemmas");
    }

    @Test
    @DisplayName("All relations have valid patterns")
    void testAllRelationsHavePatterns() {
        GrammarConfigLoader grammarConfig = GrammarConfigLoader.createDefaultEnglish();

        for (GrammarConfigLoader.RelationConfig rel : grammarConfig.getRelations()) {
            assertNotNull(rel.pattern(), "Relation " + rel.id() + " should have pattern");
            assertFalse(rel.pattern().isEmpty(), "Relation " + rel.id() + " pattern should not be empty");
        }
    }

    @Test
    @DisplayName("Relations have head and collocate positions")
    void testRelationsHavePositions() {
        GrammarConfigLoader grammarConfig = GrammarConfigLoader.createDefaultEnglish();

        var rel = grammarConfig.getRelation("noun_adj_predicates").orElse(null);
        assertNotNull(rel, "noun_adj_predicates should exist");

        // Check positions are valid
        assertTrue(rel.headPosition() >= 1, "headPosition should be >= 1");
        assertTrue(rel.collocatePosition() >= 1, "collocatePosition should be >= 1");
    }
}

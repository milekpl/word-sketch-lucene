package pl.marcinmilkowski.word_sketch.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.model.PosGroup;
import pl.marcinmilkowski.word_sketch.model.RelationType;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RelationPatternBuilder}.
 */
@DisplayName("RelationPatternBuilder")
class RelationPatternBuilderTest {

    // ─── helpers ────────────────────────────────────────────────────────────────

    /** Creates a minimal RelationConfig for head-substitution tests. */
    private static RelationConfig headConfig(String pattern, int headPos, int collocatePos, PosGroup posGroup) {
        return new RelationConfig(
                "test_relation", "Test Relation", null,
                pattern, headPos, collocatePos,
                false, 0,
                Optional.of(RelationType.SURFACE),
                true, posGroup);
    }

    /** Creates a config with a specific collocate PosGroup and RelationType (for reverse-pattern tests). */
    private static RelationConfig reverseConfig(PosGroup posGroup, Optional<RelationType> relationType) {
        return new RelationConfig(
                "rev_rel", null, null,
                "[xpos=\"NN.*\"] [xpos=\"JJ.*\"]", 1, 2,
                false, 0,
                relationType,
                true, posGroup);
    }

    // ─── buildFullPattern(config, headword) ─────────────────────────────────────

    @Test
    @DisplayName("buildFullPattern substitutes headword into head position")
    void buildFullPattern_substitutesHeadword() {
        RelationConfig config = headConfig(
                "[xpos=\"NN.*\"] [lemma=\"be\"] [xpos=\"JJ.*\"]", 1, 3, PosGroup.ADJ);

        String result = RelationPatternBuilder.buildFullPattern(config, "theory");

        assertEquals("[lemma=\"theory\" & xpos=\"NN.*\"] [lemma=\"be\"] [xpos=\"JJ.*\"]", result);
    }

    @Test
    @DisplayName("buildFullPattern with null headword returns original pattern unchanged")
    void buildFullPattern_nullHeadword_returnsOriginalPattern() {
        String pattern = "[xpos=\"NN.*\"] [lemma=\"be\"] [xpos=\"JJ.*\"]";
        RelationConfig config = headConfig(pattern, 1, 3, PosGroup.ADJ);

        String result = RelationPatternBuilder.buildFullPattern(config, null);

        assertEquals(pattern, result);
    }

    @Test
    @DisplayName("buildFullPattern with blank headword returns original pattern unchanged")
    void buildFullPattern_blankHeadword_returnsOriginalPattern() {
        String pattern = "[xpos=\"NN.*\"] [lemma=\"be\"] [xpos=\"JJ.*\"]";
        RelationConfig config = headConfig(pattern, 1, 3, PosGroup.ADJ);

        String result = RelationPatternBuilder.buildFullPattern(config, "   ");

        assertEquals(pattern, result);
    }

    // ─── buildFullPattern(config, headword, collocateLemma) ─────────────────────

    @Test
    @DisplayName("buildFullPattern substitutes both head and collocate lemmas")
    void buildFullPattern_substitutesHeadAndCollocate() {
        RelationConfig config = headConfig(
                "[xpos=\"NN.*\"] [lemma=\"be\"] [xpos=\"JJ.*\"]", 1, 3, PosGroup.ADJ);

        String result = RelationPatternBuilder.buildFullPattern(config, "theory", "correct");

        assertEquals(
                "[lemma=\"theory\" & xpos=\"NN.*\"] [lemma=\"be\"] [lemma=\"correct\" & xpos=\"JJ.*\"]",
                result);
    }

    @Test
    @DisplayName("buildFullPattern with null collocateLemma falls back to 2-arg overload")
    void buildFullPattern_nullCollocateLemma_fallsBackToTwoArgOverload() {
        RelationConfig config = headConfig(
                "[xpos=\"NN.*\"] [lemma=\"be\"] [xpos=\"JJ.*\"]", 1, 3, PosGroup.ADJ);

        String threeArg = RelationPatternBuilder.buildFullPattern(config, "theory", null);
        String twoArg   = RelationPatternBuilder.buildFullPattern(config, "theory");

        assertEquals(twoArg, threeArg);
        assertEquals("[lemma=\"theory\" & xpos=\"NN.*\"] [lemma=\"be\"] [xpos=\"JJ.*\"]", threeArg);
    }

    @Test
    @DisplayName("buildFullPattern with blank collocateLemma falls back to 2-arg overload")
    void buildFullPattern_blankCollocateLemma_fallsBackToTwoArgOverload() {
        RelationConfig config = headConfig(
                "[xpos=\"NN.*\"] [lemma=\"be\"] [xpos=\"JJ.*\"]", 1, 3, PosGroup.ADJ);

        String threeArg = RelationPatternBuilder.buildFullPattern(config, "theory", "  ");
        String twoArg   = RelationPatternBuilder.buildFullPattern(config, "theory");

        assertEquals(twoArg, threeArg);
    }

    // ─── buildCollocateReversePattern ───────────────────────────────────────────

    @Test
    @DisplayName("buildCollocateReversePattern returns JJ xpos pattern for ADJ collocate")
    void buildCollocateReversePattern_adj_returnsJjPattern() {
        RelationConfig config = reverseConfig(PosGroup.ADJ, Optional.of(RelationType.SURFACE));

        assertEquals("[xpos=\"JJ.*\"]", RelationPatternBuilder.buildCollocateReversePattern(config));
    }

    @Test
    @DisplayName("buildCollocateReversePattern returns VB xpos pattern for VERB collocate")
    void buildCollocateReversePattern_verb_returnsVbPattern() {
        RelationConfig config = reverseConfig(PosGroup.VERB, Optional.of(RelationType.SURFACE));

        assertEquals("[xpos=\"VB.*\"]", RelationPatternBuilder.buildCollocateReversePattern(config));
    }

    @Test
    @DisplayName("buildCollocateReversePattern returns NN xpos pattern for NOUN collocate")
    void buildCollocateReversePattern_noun_returnsNnPattern() {
        RelationConfig config = reverseConfig(PosGroup.NOUN, Optional.of(RelationType.SURFACE));

        assertEquals("[xpos=\"NN.*\"]", RelationPatternBuilder.buildCollocateReversePattern(config));
    }

    @Test
    @DisplayName("buildCollocateReversePattern returns RB xpos pattern for ADV collocate")
    void buildCollocateReversePattern_adv_returnsRbPattern() {
        RelationConfig config = reverseConfig(PosGroup.ADV, Optional.of(RelationType.SURFACE));

        assertEquals("[xpos=\"RB.*\"]", RelationPatternBuilder.buildCollocateReversePattern(config));
    }

    @Test
    @DisplayName("buildCollocateReversePattern throws IllegalStateException when relationType is absent")
    void buildCollocateReversePattern_absentRelationType_throwsIllegalStateException() {
        RelationConfig config = reverseConfig(PosGroup.ADJ, Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> RelationPatternBuilder.buildCollocateReversePattern(config));
    }

    // ─── computeCollocatePosGroup ────────────────────────────────────────────────

    @Test
    @DisplayName("computeCollocatePosGroup returns ADJ for JJ pattern")
    void computeCollocatePosGroup_jjPattern_returnsAdj() {
        assertEquals(PosGroup.ADJ, RelationPatternBuilder.computeCollocatePosGroup("[xpos=\"JJ.*\"]"));
    }

    @Test
    @DisplayName("computeCollocatePosGroup returns VERB for VB pattern")
    void computeCollocatePosGroup_vbPattern_returnsVerb() {
        assertEquals(PosGroup.VERB, RelationPatternBuilder.computeCollocatePosGroup("[xpos=\"VB.*\"]"));
    }

    @Test
    @DisplayName("computeCollocatePosGroup returns NOUN for NN pattern")
    void computeCollocatePosGroup_nnPattern_returnsNoun() {
        assertEquals(PosGroup.NOUN, RelationPatternBuilder.computeCollocatePosGroup("[xpos=\"NN.*\"]"));
    }

    @Test
    @DisplayName("computeCollocatePosGroup returns ADV for RB pattern")
    void computeCollocatePosGroup_rbPattern_returnsAdv() {
        assertEquals(PosGroup.ADV, RelationPatternBuilder.computeCollocatePosGroup("[xpos=\"RB.*\"]"));
    }

    @Test
    @DisplayName("computeCollocatePosGroup returns OTHER for null pattern")
    void computeCollocatePosGroup_null_returnsOther() {
        assertEquals(PosGroup.OTHER, RelationPatternBuilder.computeCollocatePosGroup(null));
    }

    @Test
    @DisplayName("computeCollocatePosGroup returns OTHER for unrecognized pattern")
    void computeCollocatePosGroup_unrecognizedPattern_returnsOther() {
        assertEquals(PosGroup.OTHER, RelationPatternBuilder.computeCollocatePosGroup("[lemma=\"foo\"]"));
    }
}

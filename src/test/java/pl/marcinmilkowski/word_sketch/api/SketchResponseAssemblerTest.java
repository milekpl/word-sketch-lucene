package pl.marcinmilkowski.word_sketch.api;

import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.api.model.CollocateEntry;
import pl.marcinmilkowski.word_sketch.api.model.RelationEntry;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.model.RelationType;
import pl.marcinmilkowski.word_sketch.model.PosGroup;
import pl.marcinmilkowski.word_sketch.model.sketch.WordSketchResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SketchResponseAssemblerTest {

    private static RelationConfig surfaceRelation() {
        return new RelationConfig(
                "adj_modifier", "Adjective modifier", null,
                "[xpos=\"JJ.*\"]", 1, 2, false, 0,
                RelationType.SURFACE, PosGroup.ADJ);
    }

    private static RelationConfig depRelation() {
        return new RelationConfig(
                "subject_of", "Subject of", "Noun as subject of a verb",
                "[deprel=\"nsubj\"]", 1, 2, false, 0,
                RelationType.DEP, PosGroup.VERB);
    }

    // ── buildSurfaceRelationEntry ─────────────────────────────────────────────

    @Test
    void buildSurfaceRelationEntry_populatedCollocations_hasCorrectFields() {
        RelationConfig rel = surfaceRelation();
        List<CollocateEntry> collocations = List.of(
                new CollocateEntry("important", 200, 9.5, "adj"),
                new CollocateEntry("novel", 100, 7.0, "adj"));

        RelationEntry entry = SketchResponseAssembler.buildSurfaceRelationEntry(rel, collocations, 300L);

        assertEquals("adj_modifier", entry.id());
        assertEquals("Adjective modifier", entry.name());
        assertEquals("[xpos=\"JJ.*\"]", entry.pattern());
        assertEquals("adj", entry.headPosGroup());
        assertEquals("adj", entry.collocatePosGroup());
        assertEquals(300L, entry.totalMatches());
        assertEquals(2, entry.collocations().size());
        assertNull(entry.description(), "Surface relation should have no description");
        assertNull(entry.deprel(), "Surface relation should have no deprel");
    }

    @Test
    void buildSurfaceRelationEntry_usesRelationTypeLabelNotName() {
        RelationEntry entry = SketchResponseAssembler.buildSurfaceRelationEntry(
                surfaceRelation(), List.of(), 0L);

        // RelationType.SURFACE.label() == "surface", not "SURFACE"
        assertEquals(RelationType.SURFACE.label(), entry.relationType());
        assertNotEquals(RelationType.SURFACE.name(), entry.relationType(),
                "Should use label() lowercase form, not name() uppercase form");
    }

    @Test
    void buildSurfaceRelationEntry_emptyCollocations_producesEntryWithZeroMatches() {
        RelationEntry entry = SketchResponseAssembler.buildSurfaceRelationEntry(
                surfaceRelation(), List.of(), 0L);

        assertTrue(entry.collocations().isEmpty());
        assertEquals(0L, entry.totalMatches());
    }

    // ── buildDepRelationEntry ─────────────────────────────────────────────────

    @Test
    void buildDepRelationEntry_populatedResults_sumsFrequenciesForTotalMatches() {
        RelationConfig rel = depRelation();
        List<WordSketchResult> results = List.of(
                new WordSketchResult("suggest", "verb", 150L, 8.0, 0.01, List.of()),
                new WordSketchResult("indicate", "verb", 50L, 6.5, 0.005, List.of()));
        List<CollocateEntry> collocations = List.of(
                new CollocateEntry("suggest", 150, 8.0, "verb"),
                new CollocateEntry("indicate", 50, 6.5, "verb"));

        RelationEntry entry = SketchResponseAssembler.buildDepRelationEntry(rel, results, collocations);

        assertEquals("subject_of", entry.id());
                assertEquals("other", entry.headPosGroup());
                assertEquals("verb", entry.collocatePosGroup());
        assertEquals(200L, entry.totalMatches(), "totalMatches should be sum of all result frequencies");
        assertEquals(2, entry.collocations().size());
        assertEquals("Noun as subject of a verb", entry.description());
    }

    @Test
    void buildDepRelationEntry_usesRelationTypeLabelNotName() {
        RelationEntry entry = SketchResponseAssembler.buildDepRelationEntry(
                depRelation(), List.of(), List.of());

        // RelationType.DEP.label() == "dep", not "DEP"
        assertEquals(RelationType.DEP.label(), entry.relationType());
        assertNotEquals(RelationType.DEP.name(), entry.relationType(),
                "Should use label() lowercase form, not name() uppercase form");
    }

    @Test
    void buildDepRelationEntry_emptyResults_zeroTotalMatches() {
        RelationEntry entry = SketchResponseAssembler.buildDepRelationEntry(
                depRelation(), List.of(), List.of());

        assertEquals(0L, entry.totalMatches());
        assertTrue(entry.collocations().isEmpty());
    }

    // ── toCollocateEntry ──────────────────────────────────────────────────────

    @Test
    void toCollocateEntry_mapsAllFields() {
        WordSketchResult result = new WordSketchResult("important", "adj", 200L, 9.5, 0.02, List.of());

        CollocateEntry entry = SketchResponseAssembler.toCollocateEntry(result);

        assertEquals("important", entry.lemma());
        assertEquals(200L, entry.frequency());
        assertEquals(9.5, entry.logDice(), 0.001);
        assertEquals("adj", entry.pos());
    }

    @Test
    void toCollocateEntry_unknownPos_preservedAsIs() {
        WordSketchResult result = new WordSketchResult(
                "xyz", WordSketchResult.UNKNOWN_POS, 1L, 0.0, 0.0, List.of());

        CollocateEntry entry = SketchResponseAssembler.toCollocateEntry(result);

        assertEquals(WordSketchResult.UNKNOWN_POS, entry.pos());
    }
}

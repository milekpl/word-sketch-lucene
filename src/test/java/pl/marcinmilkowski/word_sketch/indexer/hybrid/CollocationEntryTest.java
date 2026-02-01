package pl.marcinmilkowski.word_sketch.indexer.hybrid;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CollocationEntry record.
 */
class CollocationEntryTest {

    @Test
    @DisplayName("CollocationEntry should store headword and collocates")
    void testBasicFields() {
        List<Collocation> collocates = List.of(
            new Collocation("dog", "NOUN", 50, 1000, 8.5f),
            new Collocation("cat", "NOUN", 40, 900, 7.8f)
        );

        CollocationEntry entry = new CollocationEntry("animal", 5000, collocates);

        assertEquals("animal", entry.headword());
        assertEquals(5000, entry.headwordFrequency());
        assertEquals(2, entry.collocates().size());
    }

    @Test
    @DisplayName("size() should return collocate count")
    void testSize() {
        List<Collocation> collocates = List.of(
            new Collocation("a", "DET", 100, 10000, 5.0f),
            new Collocation("the", "DET", 150, 15000, 6.0f),
            new Collocation("an", "DET", 80, 8000, 4.5f)
        );

        CollocationEntry entry = new CollocationEntry("word", 2000, collocates);
        assertEquals(3, entry.size());
    }

    @Test
    @DisplayName("isEmpty() should detect empty entries")
    void testIsEmpty() {
        CollocationEntry empty = new CollocationEntry("rare", 10, List.of());
        assertTrue(empty.isEmpty());

        CollocationEntry notEmpty = new CollocationEntry("common", 10000,
            List.of(new Collocation("test", "NOUN", 5, 100, 3.0f)));
        assertFalse(notEmpty.isEmpty());
    }

    @Test
    @DisplayName("topN() should return top N collocates")
    void testTopN() {
        List<Collocation> collocates = List.of(
            new Collocation("first", "ADJ", 100, 1000, 10.0f),
            new Collocation("second", "ADJ", 90, 900, 9.0f),
            new Collocation("third", "ADJ", 80, 800, 8.0f),
            new Collocation("fourth", "ADJ", 70, 700, 7.0f),
            new Collocation("fifth", "ADJ", 60, 600, 6.0f)
        );

        CollocationEntry entry = new CollocationEntry("word", 5000, collocates);

        List<Collocation> top3 = entry.topN(3);
        assertEquals(3, top3.size());
        assertEquals("first", top3.get(0).lemma());
        assertEquals("second", top3.get(1).lemma());
        assertEquals("third", top3.get(2).lemma());

        // N > size should return all
        List<Collocation> top10 = entry.topN(10);
        assertEquals(5, top10.size());
    }

    @Test
    @DisplayName("filterByLogDice() should filter by minimum logDice")
    void testFilterByLogDice() {
        List<Collocation> collocates = List.of(
            new Collocation("high", "ADJ", 100, 1000, 9.5f),
            new Collocation("medium", "ADJ", 80, 800, 7.0f),
            new Collocation("low", "ADJ", 60, 600, 4.5f)
        );

        CollocationEntry entry = new CollocationEntry("word", 3000, collocates);

        List<Collocation> filtered = entry.filterByLogDice(7.0);
        assertEquals(2, filtered.size());
        assertEquals("high", filtered.get(0).lemma());
        assertEquals("medium", filtered.get(1).lemma());

        // All pass
        List<Collocation> all = entry.filterByLogDice(0.0);
        assertEquals(3, all.size());

        // None pass
        List<Collocation> none = entry.filterByLogDice(100.0);
        assertEquals(0, none.size());
    }

    @Test
    @DisplayName("Empty collocate list should be handled")
    void testEmptyCollocates() {
        CollocationEntry entry = new CollocationEntry("word", 100, List.of());

        assertEquals(0, entry.size());
        assertTrue(entry.isEmpty());
        assertEquals(0, entry.topN(5).size());
        assertEquals(0, entry.filterByLogDice(5.0).size());
    }
}

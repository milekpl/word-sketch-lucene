package pl.marcinmilkowski.word_sketch.indexer.hybrid;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Collocation record.
 */
class CollocationTest {

    @Test
    @DisplayName("Collocation should store all fields correctly")
    void testCollocationFields() {
        Collocation coll = new Collocation("house", "NOUN", 123, 45678, 9.876f);

        assertEquals("house", coll.lemma());
        assertEquals("NOUN", coll.pos());
        assertEquals(123, coll.cooccurrence());
        assertEquals(45678, coll.frequency());
        assertEquals(9.876f, coll.logDice(), 0.001f);
    }

    @Test
    @DisplayName("Collocations should sort by logDice descending")
    void testComparable() {
        Collocation a = new Collocation("a", "NOUN", 10, 100, 5.0f);
        Collocation b = new Collocation("b", "VERB", 20, 200, 8.0f);
        Collocation c = new Collocation("c", "ADJ", 15, 150, 6.5f);

        List<Collocation> list = List.of(a, b, c);
        List<Collocation> sorted = list.stream().sorted().toList();

        assertEquals("b", sorted.get(0).lemma());  // Highest logDice
        assertEquals("c", sorted.get(1).lemma());
        assertEquals("a", sorted.get(2).lemma());  // Lowest logDice
    }

    @Test
    @DisplayName("toString should format correctly")
    void testToString() {
        Collocation coll = new Collocation("test", "NOUN", 42, 1000, 7.5f);
        String str = coll.toString();

        assertNotNull(str);
        assertTrue(str.length() > 0);
    }
}

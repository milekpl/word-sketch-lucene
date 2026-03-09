package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.utils.LongIntHashMap;
import static org.junit.jupiter.api.Assertions.*;

public class LongIntHashMapTest {

    @Test
    public void testPutAndGet() {
        LongIntHashMap map = new LongIntHashMap();
        map.put(42L, 100);
        assertEquals(100, map.get(42L));
    }

    @Test
    public void testGetMissingKeyReturnsZero() {
        LongIntHashMap map = new LongIntHashMap();
        assertEquals(0, map.get(999L));
    }

    @Test
    public void testOverwriteExistingKey() {
        LongIntHashMap map = new LongIntHashMap();
        map.put(1L, 10);
        map.put(1L, 20);
        assertEquals(20, map.get(1L));
        assertEquals(1, map.size());
    }

    @Test
    public void testSizeTracking() {
        LongIntHashMap map = new LongIntHashMap();
        assertEquals(0, map.size());
        map.put(1L, 1);
        assertEquals(1, map.size());
        map.put(2L, 2);
        assertEquals(2, map.size());
        map.put(1L, 99); // overwrite, size unchanged
        assertEquals(2, map.size());
    }

    @Test
    public void testContainsKey() {
        LongIntHashMap map = new LongIntHashMap();
        assertFalse(map.containsKey(7L));
        map.put(7L, 42);
        assertTrue(map.containsKey(7L));
    }

    @Test
    public void testHandlesCollisionsWithManyEntries() {
        LongIntHashMap map = new LongIntHashMap(4); // small initial capacity forces rehash
        int count = 200;
        for (int i = 0; i < count; i++) {
            map.put((long) i, i * 10);
        }
        assertEquals(count, map.size());
        for (int i = 0; i < count; i++) {
            assertEquals(i * 10, map.get((long) i), "Wrong value for key " + i);
        }
    }

    @Test
    public void testNegativeKeys() {
        LongIntHashMap map = new LongIntHashMap();
        map.put(-1L, 7);
        map.put(-1000000L, 8);
        assertEquals(7, map.get(-1L));
        assertEquals(8, map.get(-1000000L));
    }

    @Test
    public void testLargeKeys() {
        LongIntHashMap map = new LongIntHashMap();
        long bigKey = Long.MAX_VALUE;
        map.put(bigKey, 42);
        assertEquals(42, map.get(bigKey));
    }
}

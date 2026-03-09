package pl.marcinmilkowski.word_sketch.utils;

/**
 * A memory-efficient open-addressing hash map from long keys to int values.
 * Uses linear probing and returns 0 for absent keys.
 */
public class LongIntHashMap {

    private static final long EMPTY_KEY = Long.MIN_VALUE;
    private static final int DEFAULT_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;

    private long[] keys;
    private int[] values;
    private int size;
    private int threshold;

    public LongIntHashMap() {
        this(DEFAULT_CAPACITY);
    }

    public LongIntHashMap(int initialCapacity) {
        int cap = nextPowerOfTwo(Math.max(initialCapacity, DEFAULT_CAPACITY));
        keys = new long[cap];
        values = new int[cap];
        java.util.Arrays.fill(keys, EMPTY_KEY);
        threshold = (int) (cap * LOAD_FACTOR);
    }

    public void put(long key, int value) {
        if (size >= threshold) {
            rehash();
        }
        int idx = index(key);
        while (keys[idx] != EMPTY_KEY && keys[idx] != key) {
            idx = (idx + 1) & (keys.length - 1);
        }
        if (keys[idx] == EMPTY_KEY) {
            size++;
        }
        keys[idx] = key;
        values[idx] = value;
    }

    /** Returns 0 if the key is absent. */
    public int get(long key) {
        int idx = index(key);
        while (keys[idx] != EMPTY_KEY) {
            if (keys[idx] == key) {
                return values[idx];
            }
            idx = (idx + 1) & (keys.length - 1);
        }
        return 0;
    }

    public boolean containsKey(long key) {
        int idx = index(key);
        while (keys[idx] != EMPTY_KEY) {
            if (keys[idx] == key) {
                return true;
            }
            idx = (idx + 1) & (keys.length - 1);
        }
        return false;
    }

    public int size() {
        return size;
    }

    private int index(long key) {
        int h = Long.hashCode(key);
        return (h ^ (h >>> 16)) & (keys.length - 1);
    }

    private void rehash() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        int newCap = keys.length * 2;
        keys = new long[newCap];
        values = new int[newCap];
        java.util.Arrays.fill(keys, EMPTY_KEY);
        threshold = (int) (newCap * LOAD_FACTOR);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY_KEY) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    private static int nextPowerOfTwo(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }
}

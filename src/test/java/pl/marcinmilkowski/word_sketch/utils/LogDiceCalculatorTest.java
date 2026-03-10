package pl.marcinmilkowski.word_sketch.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LogDiceCalculator.
 * 
 * LogDice formula: log2(2 * f(AB) / (f(A) + f(B))) + 14
 * Where:
 * - f(AB) = co-occurrence frequency (collocate with headword)
 * - f(A) = total headword frequency in corpus
 * - f(B) = total collocate frequency in corpus
 * 
 * Expected range: 0 to 14 (14 = perfect association)
 */
class LogDiceCalculatorTest {

    @Test
    @DisplayName("Perfect association: collocate only occurs with headword")
    void testPerfectAssociation() {
        // Perfect association: collocate only occurs with headword
        double logDice = LogDiceCalculator.compute(100, 100, 100);
        // log2(2 * 100 / (100 + 100)) + 14 = log2(1) + 14 = 0 + 14 = 14
        assertEquals(14.0, logDice, 0.001);
    }

    @Test
    void testModerateAssociation() {
        // Moderate association
        double logDice = LogDiceCalculator.compute(500, 1000, 1500);
        // log2(2 * 500 / (1000 + 1500)) + 14 = log2(0.4) + 14
        assertTrue(logDice > 12 && logDice < 14);
    }

    @Test
    void testWeakAssociation() {
        // Weak association: collocate occurs rarely with headword
        double logDice = LogDiceCalculator.compute(10, 1000, 50000);
        // Should be low but positive
        assertTrue(logDice > 0 && logDice < 10);
    }

    @Test
    void testZeroCollocateFrequency() {
        double logDice = LogDiceCalculator.compute(0, 1000, 1000);
        assertEquals(0.0, logDice, 0.001);
    }

    @Test
    void testZeroHeadwordFrequency() {
        double logDice = LogDiceCalculator.compute(100, 0, 100);
        assertEquals(0.0, logDice, 0.001);
    }

    @Test
    void testZeroCollocateTotal() {
        double logDice = LogDiceCalculator.compute(100, 100, 0);
        assertEquals(0.0, logDice, 0.001);
    }

    @Test
    void testNegativeFrequency() {
        // Should handle edge cases gracefully
        double logDice = LogDiceCalculator.compute(-10, 100, 100);
        assertEquals(0.0, logDice, 0.001);
    }

    @Test
    void testRelativeFrequency() {
        double relFreq = LogDiceCalculator.relativeFrequency(100, 1000);
        assertEquals(0.1, relFreq, 0.001);
    }

    @Test
    void testRelativeFrequencyZeroHeadword() {
        double relFreq = LogDiceCalculator.relativeFrequency(100, 0);
        assertEquals(0.0, relFreq, 0.001);
    }

    @Test
    void testLongParameters() {
        // Test with long parameters
        long collocateFreq = 5000L;
        long headwordFreq = 10000L;
        long collocateTotal = 15000L;

        double logDice = LogDiceCalculator.compute(collocateFreq, headwordFreq, collocateTotal);
        assertTrue(logDice > 0);
    }

    // =====================================================
    // Additional tests for scaled frequencies and real corpus scenarios
    // =====================================================

    @Test
    @DisplayName("LogDice should be symmetric: swapping f(A) and f(B) gives same result")
    void testSymmetry() {
        double logDice1 = LogDiceCalculator.compute(500, 10000, 20000);
        double logDice2 = LogDiceCalculator.compute(500, 20000, 10000);
        assertEquals(logDice1, logDice2, 0.001,
            "LogDice should be symmetric in f(A) and f(B)");
    }

    @Test
    @DisplayName("Higher co-occurrence frequency increases logDice monotonically")
    void testMonotonicity() {
        double logDice10 = LogDiceCalculator.compute(10, 100000, 50000);
        double logDice100 = LogDiceCalculator.compute(100, 100000, 50000);
        double logDice1000 = LogDiceCalculator.compute(1000, 100000, 50000);
        double logDice10000 = LogDiceCalculator.compute(10000, 100000, 50000);

        assertTrue(logDice100 > logDice10, "100 > 10 should increase logDice");
        assertTrue(logDice1000 > logDice100, "1000 > 100 should increase logDice");
        assertTrue(logDice10000 > logDice1000, "10000 > 1000 should increase logDice");
    }

    @Test
    @DisplayName("Real corpus: 'best book' collocation in 250M token corpus")
    void testRealCorpusBestBook() {
        // From actual query: book appears ~111K times, best appears ~319K times
        // Co-occurrence ~1300 times (estimated from sampling)
        double logDice = LogDiceCalculator.compute(1300, 111231, 318701);

        assertTrue(logDice >= 6 && logDice <= 8,
            String.format("Expected logDice 6-8 for 'best book', got: %.2f", logDice));
    }

    @Test
    @DisplayName("Real corpus: function word 'the' should have lower logDice than content words")
    void testFunctionWordCollocation() {
        // "the" appears ~15M times in a 250M corpus (very common)
        // "book" appears ~111K times
        // Co-occurrence might be ~20K
        double logDiceThe = LogDiceCalculator.compute(20000, 111000, 15_000_000);

        // Compare to a content word like "phone" with similar co-occurrence
        double logDicePhone = LogDiceCalculator.compute(5000, 111000, 50000);

        // "the" should have lower logDice than a content word collocation
        assertTrue(logDiceThe < logDicePhone,
            String.format("Function word 'the' (%.2f) should have lower logDice than content word 'phone' (%.2f)",
                logDiceThe, logDicePhone));
        
        // "the" logDice should still be moderate (not 0) because it co-occurs frequently
        assertTrue(logDiceThe >= 3 && logDiceThe <= 7,
            String.format("Function word logDice expected 3-7, got: %.2f", logDiceThe));
    }

    @Test
    @DisplayName("Real corpus: content word collocation should score well")
    void testContentWordCollocation() {
        // "phone book" - specific collocation
        // "phone" ~50K, "book" ~111K, co-occur ~5K
        double logDice = LogDiceCalculator.compute(5000, 111000, 50000);

        assertTrue(logDice >= 8 && logDice <= 11,
            String.format("Content word 'phone book' should have logDice 8-11, got: %.2f", logDice));
    }

    @Test
    @DisplayName("Scaling sample frequencies: verify calculation")
    void testScaledSampleFrequencies() {
        // Simulating: 2000 samples from 100,000 occurrences
        // Sample shows "best" appears 26 times
        // Scale factor = 100,000 / 2,000 = 50
        // Estimated corpus frequency = 26 * 50 = 1,300
        
        long sampleFreq = 26;
        int sampleSize = 2000;
        int totalOccurrences = 100_000;
        double scaleFactor = (double) totalOccurrences / sampleSize;
        long estimatedFreq = Math.round(sampleFreq * scaleFactor);

        assertEquals(50.0, scaleFactor, 0.001);
        assertEquals(1300, estimatedFreq);

        // Calculate logDice with scaled vs unscaled frequency
        long headwordFreq = 111_231;
        long collocateTotalFreq = 318_701;

        double logDiceScaled = LogDiceCalculator.compute(estimatedFreq, headwordFreq, collocateTotalFreq);
        double logDiceUnscaled = LogDiceCalculator.compute(sampleFreq, headwordFreq, collocateTotalFreq);

        // Unscaled gives wrong result (~1), scaled gives correct result (~6-7)
        assertTrue(logDiceUnscaled < 2,
            String.format("Unscaled logDice should be <2, got: %.2f", logDiceUnscaled));
        assertTrue(logDiceScaled > 5 && logDiceScaled < 8,
            String.format("Scaled logDice should be 5-8, got: %.2f", logDiceScaled));
    }

    @Test
    @DisplayName("Larger sample sizes reduce scaling variance")
    void testSampleSizeScaling() {
        // Test various sample sizes and their scaling factors
        // 2000 samples from 100000: 50x scaling - high variance
        assertEquals(50.0, 100000.0 / 2000, 0.001);
        
        // 10000 samples from 100000: 10x scaling - moderate variance
        assertEquals(10.0, 100000.0 / 10000, 0.001);
        
        // 50000 samples from 100000: 2x scaling - low variance
        assertEquals(2.0, 100000.0 / 50000, 0.001);
        
        // 100000 samples (no sampling): 1x - no variance
        assertEquals(1.0, 100000.0 / 100000, 0.001);
    }

    @Test
    @DisplayName("Edge case: extreme frequency ratios should not produce negative values")
    void testExtremeRatiosNoNegative() {
        // Very small cooccurrence relative to large corpus frequencies
        // This previously could produce negative values before overflow protection
        double logDice = LogDiceCalculator.compute(1, 10_000_000, 5_000_000);
        assertTrue(logDice >= 0,
            String.format("logDice should never be negative, got: %.2f", logDice));
    }

    @Test
    @DisplayName("Edge case: large values should not overflow")
    void testLargeValuesNoOverflow() {
        // Very large corpus (billions)
        double logDice = LogDiceCalculator.compute(1_000_000L, 500_000_000L, 300_000_000L);
        assertTrue(logDice >= 0 && logDice <= 14,
            String.format("logDice should be in range [0, 14], got: %.2f", logDice));
    }

    @Test
    @DisplayName("Very large corpus frequencies")
    void testVeryLargeCorpus() {
        // Billion-token corpus simulation
        // Headword: 1M occurrences, collocate: 5M occurrences, co-occur: 50K
        double logDice = LogDiceCalculator.compute(50_000, 1_000_000, 5_000_000);

        assertTrue(logDice >= 5 && logDice <= 9,
            String.format("Large corpus logDice expected 5-9, got: %.2f", logDice));
    }
}


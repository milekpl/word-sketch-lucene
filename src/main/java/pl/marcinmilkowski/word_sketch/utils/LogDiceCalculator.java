package pl.marcinmilkowski.word_sketch.utils;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for computing association scores for collocation analysis.
 *
 * Supported measures:
 * - logDice: Symmetric association measure, range 0-14. Formula: log2(2 * f(AB) / (f(A) + f(B))) + 14
 * - MI3: Mutual Information variant, measures unexpectedness of co-occurrence. Formula: log2((f(AB) * N) / (f(A) * f(B)))
 * - T-Score: Measures statistical significance. Formula: (f(AB) - expected) / sqrt(expected)
 * - Log-Likelihood (G2): Measures deviance from expected co-occurrence. Formula: 2 * f(AB) * log(f(AB) / expected)
 *
 * Where:
 * - f(AB) = frequency of collocate occurring with headword
 * - f(A) = total frequency of headword
 * - f(B) = total frequency of collocate
 * - N = total tokens in corpus
 * - expected = (f(A) * f(B)) / N
 */
public class LogDiceCalculator {

    /**
     * Compute the logDice score for a collocation.
     *
     * @param collocateFreq Frequency of collocate with headword (f(AB))
     * @param headwordFreq Total frequency of headword (f(A))
     * @param collocateTotal Total frequency of collocate (f(B))
     * @return logDice score (0 to ~14)
     */
    public static double compute(double collocateFreq, double headwordFreq, double collocateTotal) {
        if (headwordFreq <= 0 || collocateTotal <= 0) {
            return 0.0;
        }

        double numerator = 2.0 * collocateFreq;
        double denominator = headwordFreq + collocateTotal;

        if (denominator <= 0) {
            return 0.0;
        }

        double dice = numerator / denominator;

        // Handle edge case where dice is 0 or negative
        if (dice <= 0) {
            return 0.0;
        }

        double logDice = Math.log(dice) / Math.log(2) + 14.0;

        return Math.max(0.0, logDice);
    }

    /**
     * Compute logDice score from frequency counts.
     */
    public static double compute(long collocateFreq, long headwordFreq, long collocateTotal) {
        return compute((double) collocateFreq, (double) headwordFreq, (double) collocateTotal);
    }

    /**
     * Compute relative frequency (collocate frequency / headword frequency).
     */
    public static double relativeFrequency(long collocateFreq, long headwordFreq) {
        if (headwordFreq <= 0) {
            return 0.0;
        }
        return (double) collocateFreq / (double) headwordFreq;
    }

    /**
     * Compute MI3 (Mutual Information) score.
     *
     * MI3 = log2((f(AB) * N) / (f(A) * f(B)))
     *
     * Where:
     * - f(AB) = cooccurrence frequency
     * - f(A) = headword frequency
     * - f(B) = collocate frequency
     * - N = total tokens in corpus
     *
     * Higher values indicate stronger association. Typically scaled by 10 or 100 for readability.
     */
    public static double computeMI3(long cooccurrence, long headwordFreq, long collocateFreq, long totalTokens) {
        if (headwordFreq <= 0 || collocateFreq <= 0 || totalTokens <= 0) {
            return 0.0;
        }

        double numerator = cooccurrence * totalTokens;
        double denominator = headwordFreq * collocateFreq;

        if (denominator <= 0) {
            return 0.0;
        }

        return Math.log(numerator / denominator) / Math.log(2);
    }

    /**
     * Compute T-Score.
     *
     * T = (f(AB) - expected) / sqrt(expected)
     *
     * Where:
     * - f(AB) = cooccurrence frequency
     * - expected = (f(A) * f(B)) / N
     *
     * Higher absolute values indicate stronger statistical significance.
     */
    public static double computeTScore(long cooccurrence, long headwordFreq, long collocateFreq, long totalTokens) {
        if (headwordFreq <= 0 || collocateFreq <= 0 || totalTokens <= 0) {
            return 0.0;
        }

        double expected = (headwordFreq * (double) collocateFreq) / totalTokens;

        if (expected <= 0) {
            return 0.0;
        }

        return (cooccurrence - expected) / Math.sqrt(expected);
    }

    /**
     * Compute Log-Likelihood (G-squared) statistic.
     *
     * G2 = 2 * f(AB) * log(f(AB) / expected)
     *
     * Where:
     * - f(AB) = cooccurrence frequency
     * - expected = (f(A) * f(B)) / N
     *
     * Higher values indicate greater deviance from expected co-occurrence.
     */
    public static double computeLogLikelihood(long cooccurrence, long headwordFreq, long collocateFreq, long totalTokens) {
        if (headwordFreq <= 0 || collocateFreq <= 0 || totalTokens <= 0) {
            return 0.0;
        }

        double expected = (headwordFreq * (double) collocateFreq) / totalTokens;

        if (expected <= 0 || cooccurrence <= 0) {
            return 0.0;
        }

        double ratio = cooccurrence / expected;
        if (ratio <= 0) {
            return 0.0;
        }

        return Math.max(0.0, 2.0 * cooccurrence * Math.log(ratio));
    }

    /**
     * Result class for collocation analysis containing all association measures.
     */
    public static class CollocationResult {
        private final String lemma;
        private final String pos;
        private final long frequency;
        private final long headwordFrequency;
        private final long collocateTotal;
        private final double logDice;
        private final double relativeFrequency;
        private final double mi3;
        private final double tScore;
        private final double logLikelihood;

        public CollocationResult(String lemma, String pos, long frequency,
                                 long headwordFrequency, long collocateTotal, long totalTokens) {
            this.lemma = lemma;
            this.pos = pos;
            this.frequency = frequency;
            this.headwordFrequency = headwordFrequency;
            this.collocateTotal = collocateTotal;
            this.logDice = compute(frequency, headwordFrequency, collocateTotal);
            this.relativeFrequency = relativeFrequency(frequency, headwordFrequency);
            this.mi3 = computeMI3(frequency, headwordFrequency, collocateTotal, totalTokens);
            this.tScore = computeTScore(frequency, headwordFrequency, collocateTotal, totalTokens);
            this.logLikelihood = computeLogLikelihood(frequency, headwordFrequency, collocateTotal, totalTokens);
        }

        public String getLemma() { return lemma; }
        public String getPos() { return pos; }
        public long getFrequency() { return frequency; }
        public long getHeadwordFrequency() { return headwordFrequency; }
        public long getCollocateTotal() { return collocateTotal; }
        public double getLogDice() { return logDice; }
        public double getRelativeFrequency() { return relativeFrequency; }
        public double getMi3() { return mi3; }
        public double getTScore() { return tScore; }
        public double getLogLikelihood() { return logLikelihood; }
    }

    /**
     * Aggregated frequency counts for collocation analysis.
     */
    public static class FrequencyAggregator {
        private final Map<String, Long> lemmaFrequencies = new HashMap<>();
        private final Map<String, Long> lemmaPosFrequencies = new HashMap<>();
        private long totalHeadwordFreq = 0;
        private long totalTokens = 0;

        public void addCollocate(String lemma, String pos) {
            lemmaFrequencies.merge(lemma, 1L, Long::sum);
            String key = lemma + "|" + pos;
            lemmaPosFrequencies.merge(key, 1L, Long::sum);
        }

        public void setHeadwordFrequency(long freq) {
            this.totalHeadwordFreq = freq;
        }

        public void setTotalTokens(long totalTokens) {
            this.totalTokens = totalTokens;
        }

        public long getHeadwordFrequency() { return totalHeadwordFreq; }
        public long getTotalTokens() { return totalTokens; }

        public long getCollocateTotal(String lemma) {
            return lemmaFrequencies.getOrDefault(lemma, 0L);
        }

        public long getCollocateTotal(String lemma, String pos) {
            String key = lemma + "|" + pos;
            return lemmaPosFrequencies.getOrDefault(key, 0L);
        }

        public Map<String, Long> getLemmaFrequencies() {
            return new HashMap<>(lemmaFrequencies);
        }

        public CollocationResult getResult(String lemma, String pos, long collocateFreq) {
            return new CollocationResult(
                lemma,
                pos,
                collocateFreq,
                totalHeadwordFreq,
                getCollocateTotal(lemma),
                totalTokens
            );
        }
    }
}

package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.query.HybridQueryExecutor.Algorithm;
import pl.marcinmilkowski.word_sketch.query.WordSketchQueryExecutor.WordSketchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Performance tests comparing SAMPLE_SCAN vs SPAN_COUNT algorithms.
 * 
 * Tests on real corpus indices:
 * - d:\corpus_74m\index-quarter (smaller for testing)
 * - d:\corpus_74m\index-hybrid (full 74M token index)
 * 
 * Uses diverse test cases across frequency ranges to expose algorithm differences.
 */
public class AlgorithmPerformanceTest {

    // Path to test indices
    private static final String QUARTER_INDEX = "d:\\corpus_74m\\index-quarter";
    private static final String FULL_INDEX = "d:\\corpus_74m\\index-hybrid";

    // Test cases: (headword, pattern, description)
    private static final Object[][] TEST_CASES = {
        // High-frequency words - CRITICAL to optimize
        {"the", "1:\"N.*\" 2:\"N.*\"", "HIGH-FREQ: noun-noun (e.g., 'of the')"},
        {"be", "1:\"VB.*\" 2:\"JJ.*\"", "HIGH-FREQ: be + adjective (e.g., 'is good')"},
        {"have", "1:\"VB.*\" 2:\"N.*\"", "HIGH-FREQ: have + object"},
        
        // Medium-frequency words
        {"make", "1:\"VB.*\" 2:\"N.*\"", "MED-FREQ: make + object"},
        {"think", "1:\"VB.*\" 2:\"N.*\"", "MED-FREQ: think + object"},
        {"know", "1:\"VB.*\" 2:\"N.*\"", "MED-FREQ: know + object"},
        
        // Lower-frequency words
        {"implement", "1:\"VB.*\" 2:\"N.*\"", "LOW-FREQ: implement + object"},
        {"establish", "1:\"VB.*\" 2:\"N.*\"", "LOW-FREQ: establish + object"},
        {"demonstrate", "1:\"VB.*\" 2:\"N.*\"", "LOW-FREQ: demonstrate + object"},
    };

    /**
     * Benchmark on quarter index (smaller, faster testing).
     */
    @Test
    public void testPerformanceQuarterIndex() throws IOException {
        if (!Files.exists(Paths.get(QUARTER_INDEX))) {
            System.out.println("Skipping quarter index test - not found at: " + QUARTER_INDEX);
            return;
        }

        System.out.println("\n=== ALGORITHM PERFORMANCE BENCHMARK (QUARTER INDEX) ===");
        System.out.println("Index: quarter (has stats: " + hasStats(QUARTER_INDEX) + ")");
        System.out.println();

        benchmarkAlgorithms(QUARTER_INDEX);
    }

    /**
     * Benchmark on full index (comprehensive test).
     */
    @Test
    public void testPerformanceFullIndex() throws IOException {
        if (!Files.exists(Paths.get(FULL_INDEX))) {
            System.out.println("Skipping full index test - not found at: " + FULL_INDEX);
            return;
        }

        System.out.println("\n=== ALGORITHM PERFORMANCE BENCHMARK (FULL INDEX) ===");
        System.out.println("Index: full hybrid (has stats: " + hasStats(FULL_INDEX) + ")");
        System.out.println();

        benchmarkAlgorithms(FULL_INDEX);
    }

    private boolean hasStats(String indexPath) {
        return Files.exists(Paths.get(indexPath, "stats.bin")) || 
               Files.exists(Paths.get(indexPath, "stats.tsv"));
    }

    private void benchmarkAlgorithms(String indexPath) throws IOException {
        HybridQueryExecutor executor = new HybridQueryExecutor(indexPath);
        executor.setMaxSampleSize(5000);

        // Results collection
        List<QueryBenchmark> benchmarks = new ArrayList<>();

        System.out.println("Running benchmark suite...\n");
        System.out.printf("%-12s %-40s %-12s %-12s %-8s %-12s\n",
            "Algorithm", "Test Case", "Time(ms)", "Results", "Speedup", "Status");
        System.out.println("─".repeat(110));

        // Run each test with both algorithms
        for (Object[] testCase : TEST_CASES) {
            String headword = (String) testCase[0];
            String pattern = (String) testCase[1];
            String description = (String) testCase[2];

            // Run SAMPLE_SCAN
            executor.setAlgorithm(Algorithm.SAMPLE_SCAN);
            QueryBenchmark scanBench = runQueryBenchmark(executor, headword, pattern, description);
            benchmarks.add(scanBench);

            // Run SPAN_COUNT (only if stats available)
            boolean hasStats = executor.getStatsReader() != null;
            if (hasStats) {
                executor.setAlgorithm(Algorithm.SPAN_COUNT);
                QueryBenchmark spanBench = runQueryBenchmark(executor, headword, pattern, description);
                benchmarks.add(spanBench);

                // Calculate speedup
                if (scanBench.timeMs > 0 && spanBench.timeMs > 0) {
                    double speedup = (double) scanBench.timeMs / spanBench.timeMs;
                    String matchStatus = scanBench.resultCount == spanBench.resultCount ? "✓ MATCH" : 
                                        (Math.abs(scanBench.resultCount - spanBench.resultCount) < 5 ? "≈ CLOSE" : "✗ DIFF");
                    System.out.printf("%-12s %-40s %-12d %-12d %-8.2fx %s\n",
                        "SPAN_COUNT", description, spanBench.timeMs, spanBench.resultCount,
                        speedup, matchStatus);
                }
            } else {
                System.out.printf("%-12s %-40s %-12s %-12s %-8s %s\n",
                    "SPAN_COUNT", description, "N/A", "N/A", "N/A", "NO STATS");
            }
        }

        printSummary(benchmarks);
        executor.close();
    }

    private QueryBenchmark runQueryBenchmark(HybridQueryExecutor executor, 
                                             String headword, String pattern, String description) 
            throws IOException {
        try {
            long start = System.currentTimeMillis();
            List<WordSketchResult> results = executor.findCollocations(headword, pattern, 0, 20);
            long elapsed = System.currentTimeMillis() - start;

            System.out.printf("%-12s %-40s %-12d %-12d %-8s %s\n",
                executor.getAlgorithm().name(), description, elapsed, results.size(),
                "", "✓");

            return new QueryBenchmark(headword, pattern, executor.getAlgorithm(), elapsed, results.size());

        } catch (Exception e) {
            System.out.printf("%-12s %-40s %-12s %-12s %-8s %s\n",
                executor.getAlgorithm().name(), description, "ERROR", "0",
                "", e.getClass().getSimpleName());
            return new QueryBenchmark(headword, pattern, executor.getAlgorithm(), -1, 0);
        }
    }

    private void printSummary(List<QueryBenchmark> benchmarks) {
        System.out.println("\n" + "─".repeat(110));
        
        // Group by algorithm
        Map<Algorithm, Long> totalTime = new HashMap<>();
        Map<Algorithm, Integer> queryCount = new HashMap<>();

        for (QueryBenchmark b : benchmarks) {
            if (b.timeMs > 0) {
                totalTime.put(b.algorithm, totalTime.getOrDefault(b.algorithm, 0L) + b.timeMs);
                queryCount.put(b.algorithm, queryCount.getOrDefault(b.algorithm, 0) + 1);
            }
        }

        System.out.println("\nSUMMARY:");
        if (totalTime.containsKey(Algorithm.SAMPLE_SCAN)) {
            long scanTotal = totalTime.get(Algorithm.SAMPLE_SCAN);
            int scanCount = queryCount.get(Algorithm.SAMPLE_SCAN);
            System.out.printf("  SAMPLE_SCAN: %d queries, %.0f ms total, %.0f ms avg\n",
                scanCount, (double)scanTotal, (double)scanTotal / scanCount);
        }

        if (totalTime.containsKey(Algorithm.SPAN_COUNT)) {
            long spanTotal = totalTime.get(Algorithm.SPAN_COUNT);
            int spanCount = queryCount.get(Algorithm.SPAN_COUNT);
            System.out.printf("  SPAN_COUNT:  %d queries, %.0f ms total, %.0f ms avg\n",
                spanCount, (double)spanTotal, (double)spanTotal / spanCount);

            if (totalTime.containsKey(Algorithm.SAMPLE_SCAN)) {
                long scanTotal = totalTime.get(Algorithm.SAMPLE_SCAN);
                double speedup = (double) scanTotal / spanTotal;
                System.out.printf("  SPEEDUP: %.2fx faster with SPAN_COUNT\n", speedup);
            }
        }
    }

    /**
     * Simple benchmark result for one query.
     */
    private static class QueryBenchmark {
        final String headword;
        final String pattern;
        final Algorithm algorithm;
        final long timeMs;
        final int resultCount;

        QueryBenchmark(String headword, String pattern, Algorithm algo, long timeMs, int resultCount) {
            this.headword = headword;
            this.pattern = pattern;
            this.algorithm = algo;
            this.timeMs = timeMs;
            this.resultCount = resultCount;
        }
    }
}

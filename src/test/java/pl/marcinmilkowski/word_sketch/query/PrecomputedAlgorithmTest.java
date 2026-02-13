package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.*;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.CollocationsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.queries.spans.SpanTermQuery;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TopDocs;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for PRECOMPUTED algorithm.
 * 
 * Tests the full flow: build collocations -> query with PRECOMPUTED.
 */
class PrecomputedAlgorithmTest {

    private static final String TEST_INDEX = "target/index-quarter";
    private static Path tempCollocationsFile;
    private static HybridQueryExecutor executor;

    @BeforeAll
    static void setUp() throws IOException, InterruptedException {
        // Skip if test index doesn't exist
        if (!Files.exists(Path.of(TEST_INDEX))) {
            System.out.println("Skipping PRECOMPUTED tests: quarter index not found");
            return;
        }

        // Build small collocations index for testing
        tempCollocationsFile = Files.createTempFile("test-collocations", ".bin");
        tempCollocationsFile.toFile().deleteOnExit();

        System.out.println("Building test collocations index...");
        CollocationsBuilder builder = new CollocationsBuilder(TEST_INDEX, TEST_INDEX + "/stats.bin");
        builder.setTopK(50);
        builder.setMinFrequency(100);  // Frequent words only
        builder.setThreads(2);
        builder.build(tempCollocationsFile.toString());

        // Copy to index directory so executor can find it
        Path targetPath = Path.of(TEST_INDEX, "collocations.bin");
        Files.copy(tempCollocationsFile, targetPath, 
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // Create executor
        executor = new HybridQueryExecutor(TEST_INDEX);
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.PRECOMPUTED);
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (executor != null) {
            executor.close();
        }
        if (tempCollocationsFile != null) {
            Files.deleteIfExists(tempCollocationsFile);
        }
        // Clean up collocations.bin from index
        Path targetPath = Path.of(TEST_INDEX, "collocations.bin");
        Files.deleteIfExists(targetPath);
    }

    @Test
    @DisplayName("PRECOMPUTED should return results instantly")
    void testPrecomputedSpeed() throws IOException {
        if (!Files.exists(Path.of(TEST_INDEX))) {
            return;
        }

        long start = System.currentTimeMillis();
        List<WordSketchQueryExecutor.WordSketchResult> results = 
            executor.findCollocations("the", "[lemma=\".*\"]", 5.0, 20);
        long duration = System.currentTimeMillis() - start;

        assertNotNull(results);
        assertTrue(results.size() > 0, "Should find collocates for 'the'");
        assertTrue(duration < 100, "Should complete in < 100ms, took " + duration + "ms");
    }

    @Test
    @DisplayName("PRECOMPUTED should filter by logDice")
    void testLogDiceFilter() throws IOException {
        if (!Files.exists(Path.of(TEST_INDEX))) {
            return;
        }

        List<WordSketchQueryExecutor.WordSketchResult> highThreshold = 
            executor.findCollocations("the", "[lemma=\".*\"]", 10.0, 50);
        
        List<WordSketchQueryExecutor.WordSketchResult> lowThreshold = 
            executor.findCollocations("the", "[lemma=\".*\"]", 5.0, 50);

        assertTrue(highThreshold.size() <= lowThreshold.size(),
            "Higher logDice threshold should return fewer results");

        // All results should meet threshold
        for (var result : highThreshold) {
            assertTrue(result.getLogDice() >= 10.0);
        }
    }

    @Test
    @DisplayName("PRECOMPUTED should respect maxResults")
    void testMaxResults() throws IOException {
        if (!Files.exists(Path.of(TEST_INDEX))) {
            return;
        }

        List<WordSketchQueryExecutor.WordSketchResult> results10 = 
            executor.findCollocations("the", "[lemma=\".*\"]", 0.0, 10);
        
        List<WordSketchQueryExecutor.WordSketchResult> results5 = 
            executor.findCollocations("the", "[lemma=\".*\"]", 0.0, 5);

        assertTrue(results10.size() <= 10);
        assertTrue(results5.size() <= 5);
    }

    @Test
    @DisplayName("PRECOMPUTED should handle unknown headword")
    void testUnknownHeadword() throws IOException {
        if (!Files.exists(Path.of(TEST_INDEX))) {
            return;
        }

        List<WordSketchQueryExecutor.WordSketchResult> results = 
            executor.findCollocations("zzz_nonexistent_zzz", "[lemma=\".*\"]", 0.0, 50);

        assertNotNull(results);
        assertEquals(0, results.size());
    }

    @Test
    @DisplayName("PRECOMPUTED should apply CQL filters")
    void testCqlFiltering() throws IOException {
        if (!Files.exists(Path.of(TEST_INDEX))) {
            return;
        }

        // Get all collocates
        List<WordSketchQueryExecutor.WordSketchResult> all = 
            executor.findCollocations("be", "[lemma=\".*\"]", 5.0, 50);

        // Filter by POS
        List<WordSketchQueryExecutor.WordSketchResult> nounsOnly = 
            executor.findCollocations("be", "[pos=\"NOUN\"]", 5.0, 50);

        assertTrue(nounsOnly.size() < all.size(), 
            "POS filter should reduce results");

        // Verify all are nouns
        for (var result : nounsOnly) {
            assertEquals("NOUN", result.getPos().toUpperCase());
        }
    }

    @Test
    @DisplayName("PRECOMPUTED results should be sorted by logDice")
    void testResultsSorted() throws IOException {
        if (!Files.exists(Path.of(TEST_INDEX))) {
            return;
        }

        List<WordSketchQueryExecutor.WordSketchResult> results = 
            executor.findCollocations("the", "[lemma=\".*\"]", 0.0, 30);

        assertTrue(results.size() > 5, "Need multiple results to test sorting");

        // Verify descending order
        for (int i = 1; i < results.size(); i++) {
            double prev = results.get(i - 1).getLogDice();
            double curr = results.get(i).getLogDice();
            assertTrue(prev >= curr, 
                String.format("Not sorted: %.2f < %.2f", prev, curr));
        }
    }

    @Test
    @DisplayName("PRECOMPUTED vs SAMPLE_SCAN should give similar results")
    void testConsistencyWithSampleScan() throws IOException {
        if (!Files.exists(Path.of(TEST_INDEX))) {
            return;
        }

        // Query with PRECOMPUTED
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.PRECOMPUTED);
        List<WordSketchQueryExecutor.WordSketchResult> precomputed = 
            executor.findCollocations("house", "[lemma=\".*\"]", 7.0, 20);

        // Query with SAMPLE_SCAN
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.SAMPLE_SCAN);
        executor.setMaxSampleSize(5000);
        List<WordSketchQueryExecutor.WordSketchResult> sampleScan = 
            executor.findCollocations("house", "[lemma=\".*\"]", 7.0, 20);

        // Should have significant overlap in top results
        int overlap = 0;
        for (var p : precomputed) {
            for (var s : sampleScan) {
                if (p.getLemma().equals(s.getLemma())) {
                    overlap++;
                    break;
                }
            }
        }

        int minOverlap = Math.min(10, Math.min(precomputed.size(), sampleScan.size()));
        assertTrue(overlap >= minOverlap, 
            String.format("Expected at least %d overlap, got %d", minOverlap, overlap));

        // Reset to PRECOMPUTED
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.PRECOMPUTED);
    }

    @Test
    @DisplayName("PRECOMPUTED collocates must have at least one span example in index")
    void precomputedResultsHaveSpanExamples() throws IOException {
        if (!Files.exists(Path.of(TEST_INDEX))) {
            return;
        }

        executor.setAlgorithm(HybridQueryExecutor.Algorithm.PRECOMPUTED);
        List<WordSketchQueryExecutor.WordSketchResult> results = executor.findCollocations("house", "[lemma=\".*\"]", 0.0, 50);
        assertFalse(results.isEmpty(), "PRECOMPUTED should return some collocates for 'house'");

        // Verify each returned collocate has at least one sentence with both lemmas within window
        try (var dir = FSDirectory.open(Paths.get(TEST_INDEX));
             var reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            for (var r : results) {
                SpanTermQuery s1 = new SpanTermQuery(new Term("lemma", "house"));
                SpanTermQuery s2 = new SpanTermQuery(new Term("lemma", r.getLemma()));
                SpanNearQuery near = SpanNearQuery.newUnorderedNearQuery("lemma").addClause(s1).addClause(s2).setSlop(5).build();
                TopDocs td = searcher.search(near, 1);
                assertTrue(td.scoreDocs.length > 0, "PRECOMPUTED returned collocate '" + r.getLemma() + "' with no span examples");
            }
        }
    }

    @Test
    @DisplayName("PRECOMPUTED should be much faster than SAMPLE_SCAN")
    void testPerformanceComparison() throws IOException {
        if (!Files.exists(Path.of(TEST_INDEX))) {
            return;
        }

        String[] testWords = {"the", "be", "have", "do", "say"};
        
        // Warmup
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.PRECOMPUTED);
        executor.findCollocations("test", "[lemma=\".*\"]", 5.0, 20);

        // Time PRECOMPUTED
        long precomputedStart = System.currentTimeMillis();
        for (String word : testWords) {
            executor.findCollocations(word, "[lemma=\".*\"]", 5.0, 20);
        }
        long precomputedTime = System.currentTimeMillis() - precomputedStart;

        // Time SAMPLE_SCAN
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.SAMPLE_SCAN);
        executor.setMaxSampleSize(1000);  // Small sample for faster test
        long sampleScanStart = System.currentTimeMillis();
        for (String word : testWords) {
            executor.findCollocations(word, "[lemma=\".*\"]", 5.0, 20);
        }
        long sampleScanTime = System.currentTimeMillis() - sampleScanStart;

        System.out.printf("PRECOMPUTED: %dms, SAMPLE_SCAN: %dms, speedup: %.1fx%n",
            precomputedTime, sampleScanTime, (double) sampleScanTime / precomputedTime);

        // PRECOMPUTED should be at least 5x faster
        assertTrue(sampleScanTime > precomputedTime * 5,
            String.format("Expected 5x speedup, got %.1fx", 
                (double) sampleScanTime / precomputedTime));

        // Reset
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.PRECOMPUTED);
    }

    @Test
    @DisplayName("Diagnostics: collocations-integrity top-N scan")
    void testCollocationsIntegrityTopN() throws IOException {
        if (!Files.exists(Path.of(TEST_INDEX))) {
            return;
        }

        // Ensure PRECOMPUTED loaded
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.PRECOMPUTED);

        List<java.util.Map<String, Object>> report = executor.collocationsIntegrityTopN(10);
        assertNotNull(report);

        // Report items (if any) must contain expected fields
        for (var item : report) {
            assertTrue(item.containsKey("headword"));
            assertTrue(item.containsKey("collocate_count"));
            assertTrue(item.containsKey("mismatch_count"));
            assertTrue(item.containsKey("problems"));

            var problems = (java.util.List<?>) item.get("problems");
            for (Object p : problems) {
                assertTrue(((java.util.Map<?,?>)p).containsKey("lemma"));
                assertTrue(((java.util.Map<?,?>)p).containsKey("reason"));
            }
        }

        // Also test per-head report API (single head)
        if (!report.isEmpty()) {
            String head = (String) report.get(0).get("headword");
            List<java.util.Map<String, Object>> perHead = executor.collocationsIntegrityReportFor(List.of(head), 5);
            assertNotNull(perHead);
            if (!perHead.isEmpty()) {
                assertEquals(head, perHead.get(0).get("headword"));
            }
        }
    }
}

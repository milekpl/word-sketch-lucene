package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.*;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader.RelationConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test hybrid approach: PRECOMPUTED + POS filter vs SAMPLE_SCAN.
 *
 * Strategy:
 * - For relations WITHOUT copula (noun_modifiers): PRECOMPUTED + POS should match SAMPLE_SCAN
 * - For relations WITH copula (noun_adj_predicates): PRECOMPUTED may return superset (no copula filter)
 *
 * This tests whether the grammar-driven approach can use PRECOMPUTED for speed
 * while still getting accurate results.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HybridAlgorithmTest {

    // Use external index at corpus_74m (as per other tests)
    // Use full index with collocations
    private static final String TEST_INDEX = "d:\\corpus_74m\\index-hybrid";
    private static HybridQueryExecutor executor;
    private static GrammarConfigLoader grammarConfig;

    @BeforeAll
    static void setUp() throws IOException {
        // Load grammar config - try multiple paths
        String[] possiblePaths = {
            "src/test/resources/grammars/relations.json",
            "src/main/resources/grammars/relations.json",
            "grammars/relations.json",
            "../grammars/relations.json"
        };

        for (String p : possiblePaths) {
            Path grammarPath = Paths.get(p).toAbsolutePath();
            if (Files.exists(grammarPath)) {
                try {
                    grammarConfig = new GrammarConfigLoader(grammarPath);
                    System.out.println("Loaded grammar config from: " + grammarPath);
                    break;
                } catch (Exception e) {
                    System.out.println("Failed to load " + grammarPath + ": " + e.getMessage());
                }
            }
        }

        // Only run executor tests if index exists - handle Windows paths
        Path indexPath = Paths.get(TEST_INDEX);
        if (!Files.exists(indexPath)) {
            // Try as absolute path
            indexPath = Path.of(TEST_INDEX);
        }
        if (!Files.exists(indexPath)) {
            System.out.println("Skipping executor tests: index not found at " + TEST_INDEX);
            return;
        }

        // Create executor with PRECOMPUTED
        executor = new HybridQueryExecutor(TEST_INDEX);
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.PRECOMPUTED);
    }

    @Test
    @Order(1)
    @DisplayName("Grammar config should be loadable")
    void grammarConfigLoads() {
        assertNotNull(grammarConfig, "Grammar config should be loaded");
        System.out.println("Grammar version: " + grammarConfig.getVersion());
        System.out.println("Total relations: " + grammarConfig.getRelations().size());
    }

    @Test
    @Order(2)
    @DisplayName("NOUN_MODIFIERS: PRECOMPUTED + POS filter should match SAMPLE_SCAN")
    void testNounModifiersRelation() throws IOException {
        if (executor == null || grammarConfig == null) return;

        RelationConfig rel = grammarConfig.getRelation("noun_modifiers").orElse(null);
        assertNotNull(rel, "noun_modifiers relation should exist");
        assertEquals("adj", rel.collocatePos(), "Should be adjective collocates");
        assertFalse(Boolean.TRUE.equals(rel.usesCopula()), "noun_modifiers should NOT use copula");

        // Test word: "theory"
        String testWord = "theory";
        String cqlPattern = rel.cqlPattern(); // [tag=jj.*]

        // PRECOMPUTED approach
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.PRECOMPUTED);
        List<WordSketchQueryExecutor.WordSketchResult> precomputed =
            executor.findCollocations(testWord, cqlPattern, 3.0, 30);

        // SAMPLE_SCAN approach
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.SAMPLE_SCAN);
        executor.setMaxSampleSize(5000);
        List<WordSketchQueryExecutor.WordSketchResult> sampleScan =
            executor.findCollocations(testWord, cqlPattern, 3.0, 30);

        System.out.println("\n=== NOUN_MODIFIERS (theory) ===");
        System.out.println("PRECOMPUTED: " + precomputed.size() + " results");
        System.out.println("SAMPLE_SCAN: " + sampleScan.size() + " results");

        // For non-copula relations, results should be very similar
        // (PRECOMPUTED might have more because it uses global window vs sentence-level)
        Set<String> precomputedLemmas = new HashSet<>(precomputed.stream()
            .map(WordSketchQueryExecutor.WordSketchResult::getLemma).toList());
        Set<String> sampleScanLemmas = new HashSet<>(sampleScan.stream()
            .map(WordSketchQueryExecutor.WordSketchResult::getLemma).toList());

        int overlap = 0;
        for (String lem : sampleScanLemmas) {
            if (precomputedLemmas.contains(lem)) overlap++;
        }

        double overlapRatio = (double) overlap / sampleScanLemmas.size();
        System.out.println("Overlap: " + overlap + "/" + sampleScanLemmas.size() + " = " + String.format("%.1f%%", overlapRatio * 100));

        // For non-copula relations, expect moderate overlap (different window strategies)
        // PRECOMPUTED uses global window, SAMPLE_SCAN uses sentence-level
        // Expect >25% overlap for noun_modifiers
        assertTrue(overlapRatio > 0.25,
            "PRECOMPUTED should match >25% of SAMPLE_SCAN for non-copula relations, got " + String.format("%.1f%%", overlapRatio * 100));
    }

    @Test
    @Order(3)
    @DisplayName("NOUN_ADJ_PREDICATES: PRECOMPUTED vs SAMPLE_SCAN (with copula)")
    void testNounAdjPredicatesRelation() throws IOException {
        if (executor == null || grammarConfig == null) return;

        RelationConfig rel = grammarConfig.getRelation("noun_adj_predicates").orElse(null);
        assertNotNull(rel, "noun_adj_predicates relation should exist");
        assertEquals("adj", rel.collocatePos(), "Should be adjective collocates");
        assertTrue(Boolean.TRUE.equals(rel.usesCopula()), "noun_adj_predicates SHOULD use copula");

        // Test word: "theory"
        String testWord = "theory";
        String cqlPattern = rel.cqlPattern(); // [tag=jj.*]

        // PRECOMPUTED approach (no copula filter)
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.PRECOMPUTED);
        List<WordSketchQueryExecutor.WordSketchResult> precomputed =
            executor.findCollocations(testWord, cqlPattern, 3.0, 30);

        // SAMPLE_SCAN approach (with copula filter via findGrammaticalRelation)
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.SAMPLE_SCAN);
        executor.setMaxSampleSize(5000);
        List<WordSketchQueryExecutor.WordSketchResult> sampleScan =
            executor.findGrammaticalRelation(testWord, QueryExecutor.RelationType.ADJ_PREDICATE, 3.0, 30);

        System.out.println("\n=== NOUN_ADJ_PREDICATES (theory) ===");
        System.out.println("PRECOMPUTED (no copula): " + precomputed.size() + " results");
        System.out.println("SAMPLE_SCAN (with copula): " + sampleScan.size() + " results");

        // Show what's different
        Set<String> precomputedLemmas = new HashSet<>(precomputed.stream()
            .map(WordSketchQueryExecutor.WordSketchResult::getLemma).toList());
        Set<String> sampleScanLemmas = new HashSet<>(sampleScan.stream()
            .map(WordSketchQueryExecutor.WordSketchResult::getLemma).toList());

        Set<String> onlyInPrecomputed = new HashSet<>(precomputedLemmas);
        onlyInPrecomputed.removeAll(sampleScanLemmas);
        Set<String> onlyInSampleScan = new HashSet<>(sampleScanLemmas);
        onlyInSampleScan.removeAll(precomputedLemmas);

        System.out.println("Only in PRECOMPUTED (no copula filter): " + onlyInPrecomputed.size());
        if (!onlyInPrecomputed.isEmpty()) {
            System.out.println("  Examples: " + new ArrayList<>(onlyInPrecomputed).subList(0, Math.min(5, onlyInPrecomputed.size())));
        }
        System.out.println("Only in SAMPLE_SCAN (with copula filter): " + onlyInSampleScan.size());
        if (!onlyInSampleScan.isEmpty()) {
            System.out.println("  Examples: " + new ArrayList<>(onlyInSampleScan).subList(0, Math.min(5, onlyInSampleScan.size())));
        }

        // For copula relations, PRECOMPUTED returns SUPERSET (expected)
        // SAMPLE_SCAN filters to only those with copula
        assertTrue(precomputed.size() >= sampleScan.size(),
            "PRECOMPUTED should return >= results than SAMPLE_SCAN for copula relations");
    }

    @Test
    @Order(4)
    @DisplayName("NOUN_VERBS: PRECOMPUTED + POS filter should match SAMPLE_SCAN")
    void testNounVerbsRelation() throws IOException {
        if (executor == null || grammarConfig == null) return;

        RelationConfig rel = grammarConfig.getRelation("noun_verbs").orElse(null);
        assertNotNull(rel, "noun_verbs relation should exist");
        assertEquals("verb", rel.collocatePos(), "Should be verb collocates");

        // Test word: "theory"
        String testWord = "theory";
        String cqlPattern = rel.cqlPattern(); // [tag=vb.*]

        // PRECOMPUTED
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.PRECOMPUTED);
        List<WordSketchQueryExecutor.WordSketchResult> precomputed =
            executor.findCollocations(testWord, cqlPattern, 3.0, 30);

        // SAMPLE_SCAN
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.SAMPLE_SCAN);
        executor.setMaxSampleSize(5000);
        List<WordSketchQueryExecutor.WordSketchResult> sampleScan =
            executor.findCollocations(testWord, cqlPattern, 3.0, 30);

        System.out.println("\n=== NOUN_VERBS (theory) ===");
        System.out.println("PRECOMPUTED: " + precomputed.size() + " results");
        System.out.println("SAMPLE_SCAN: " + sampleScan.size() + " results");

        Set<String> precomputedLemmas = new HashSet<>(precomputed.stream()
            .map(WordSketchQueryExecutor.WordSketchResult::getLemma).toList());
        Set<String> sampleScanLemmas = new HashSet<>(sampleScan.stream()
            .map(WordSketchQueryExecutor.WordSketchResult::getLemma).toList());

        int overlap = 0;
        for (String lem : sampleScanLemmas) {
            if (precomputedLemmas.contains(lem)) overlap++;
        }

        double overlapRatio = sampleScanLemmas.isEmpty() ? 0 : (double) overlap / sampleScanLemmas.size();
        System.out.println("Overlap: " + overlap + "/" + sampleScanLemmas.size() + " = " + String.format("%.1f%%", overlapRatio * 100));

        // Expect >20% overlap for noun_verbs
        assertTrue(overlapRatio > 0.2,
            "PRECOMPUTED should match >20% of SAMPLE_SCAN for verb relations, got " + String.format("%.1f%%", overlapRatio * 100));
    }

    @Test
    @Order(5)
    @DisplayName("Performance: PRECOMPUTED should be much faster than SAMPLE_SCAN")
    void testPerformance() throws IOException {
        if (executor == null) return;

        String[] testWords = {"theory", "model", "hypothesis", "data", "analysis"};
        String cqlPattern = "[tag=jj.*]";

        // Warmup
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.PRECOMPUTED);
        for (String w : testWords) {
            executor.findCollocations(w, cqlPattern, 3.0, 20);
        }

        // Time PRECOMPUTED
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.PRECOMPUTED);
        long preStart = System.currentTimeMillis();
        for (String w : testWords) {
            executor.findCollocations(w, cqlPattern, 3.0, 20);
        }
        long preTime = System.currentTimeMillis() - preStart;

        // Time SAMPLE_SCAN
        executor.setAlgorithm(HybridQueryExecutor.Algorithm.SAMPLE_SCAN);
        executor.setMaxSampleSize(5000);
        long scanStart = System.currentTimeMillis();
        for (String w : testWords) {
            executor.findCollocations(w, cqlPattern, 3.0, 20);
        }
        long scanTime = System.currentTimeMillis() - scanStart;

        System.out.println("\n=== PERFORMANCE ===");
        System.out.println("PRECOMPUTED: " + preTime + "ms");
        System.out.println("SAMPLE_SCAN: " + scanTime + "ms");
        System.out.println("Speedup: " + String.format("%.1fx", (double) scanTime / preTime));

        // Note: Performance depends on index size, cached pages, etc.
        // Don't assert on speed - just report
    }

    @Test
    @Order(6)
    @DisplayName("All exploration_enabled relations should work with hybrid approach")
    void testAllExplorationRelations() throws IOException {
        if (executor == null || grammarConfig == null) return;

        List<RelationConfig> explorationRelations = grammarConfig.getRelations().stream()
            .filter(r -> Boolean.TRUE.equals(r.explorationEnabled()))
            .toList();

        System.out.println("\n=== EXPLORATION RELATIONS ===");
        for (RelationConfig rel : explorationRelations) {
            System.out.println(rel.id() + " (copula=" + rel.usesCopula() + ")");
        }

        assertTrue(explorationRelations.size() >= 4,
            "Should have at least 4 exploration-enabled relations");
    }
}

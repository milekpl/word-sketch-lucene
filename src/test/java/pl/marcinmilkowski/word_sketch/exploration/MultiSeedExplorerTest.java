package pl.marcinmilkowski.word_sketch.exploration;

import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.model.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MultiSeedExplorerTest {

    /** Stub executor returning canned results per lemma via executeSurfacePattern. */
    private static QueryExecutor stubExecutor(Map<String, List<QueryResults.WordSketchResult>> data) {
        return new QueryExecutor() {
            @Override public List<QueryResults.WordSketchResult> executeCollocations(
                    String lemma, String p, double m, int max) { return List.of(); }
            @Override public List<QueryResults.ConcordanceResult> executeCqlQuery(String p, int m) { return List.of(); }
            @Override public List<QueryResults.CollocateResult> executeBcqlQuery(String p, int m) { return List.of(); }
            @Override public long getTotalFrequency(String lemma) { return 0; }
            @Override public List<QueryResults.WordSketchResult> executeSurfacePattern(
                    String lemma, String pattern, double minLogDice, int max) {
                return data.getOrDefault(lemma, List.of());
            }
            @Override public List<QueryResults.WordSketchResult> executeDependencyPattern(
                    String l, String d, double m, int max, String pos) { return List.of(); }
            @Override public void close() {}
        };
    }

    private static QueryResults.WordSketchResult wsr(String lemma, double logDice, long freq) {
        return new QueryResults.WordSketchResult(lemma, "JJ", freq, logDice, 0.0, List.of());
    }

    private static RelationConfig anyRelation() {
        return GrammarConfigHelper.requireTestConfig().relations().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No relations in test grammar"));
    }

    @Test
    void explore_sharedCollocateAppearsInCoreCollocates() throws IOException {
        Map<String, List<QueryResults.WordSketchResult>> data = new LinkedHashMap<>();
        data.put("theory",  List.of(wsr("important", 8.0, 50), wsr("novel", 5.0, 20)));
        data.put("model",   List.of(wsr("important", 7.0, 40), wsr("large",  4.0, 10)));

        MultiSeedExplorer explorer = new MultiSeedExplorer(stubExecutor(data));
        ExplorationResult result = explorer.findCollocateIntersection(
                Set.of("theory", "model"), anyRelation(), 0.0, 50, 2);

        assertTrue(result.coreCollocates().stream()
                .anyMatch(c -> "important".equals(c.collocate())),
                "\"important\" shared by both seeds should appear in core collocates");
        assertTrue(result.coreCollocates().stream()
                .noneMatch(c -> "novel".equals(c.collocate())),
                "\"novel\" present only in \"theory\" should not be a core collocate");
    }

    @Test
    void explore_seedsBecomeDiscoveredNouns() throws IOException {
        Map<String, List<QueryResults.WordSketchResult>> data = Map.of(
                "theory", List.of(wsr("abstract", 7.0, 30)),
                "model",  List.of(wsr("abstract", 6.0, 20)));

        MultiSeedExplorer explorer = new MultiSeedExplorer(stubExecutor(data));
        ExplorationResult result = explorer.findCollocateIntersection(
                Set.of("theory", "model"), anyRelation(), 0.0, 50, 2);

        List<String> nounNames = result.discoveredNouns().stream()
                .map(pl.marcinmilkowski.word_sketch.model.DiscoveredNoun::noun).toList();
        assertTrue(nounNames.contains("theory"), "\"theory\" should be a discovered noun");
        assertTrue(nounNames.contains("model"),  "\"model\" should be a discovered noun");
    }

    @Test
    void explore_noSharedCollocates_coreCollocatesEmpty() throws IOException {
        Map<String, List<QueryResults.WordSketchResult>> data = Map.of(
                "theory", List.of(wsr("abstract", 7.0, 30)),
                "model",  List.of(wsr("large",    6.0, 20)));

        MultiSeedExplorer explorer = new MultiSeedExplorer(stubExecutor(data));
        ExplorationResult result = explorer.findCollocateIntersection(
                Set.of("theory", "model"), anyRelation(), 0.0, 50, 2);

        assertTrue(result.coreCollocates().isEmpty(),
                "No shared collocates → core collocates must be empty");
    }

    @Test
    void explore_emptyExecutor_returnsEmptyResult() throws IOException {
        MultiSeedExplorer explorer = new MultiSeedExplorer(stubExecutor(Map.of()));
        ExplorationResult result = explorer.findCollocateIntersection(
                Set.of("theory", "model"), anyRelation(), 0.0, 50, 1);

        assertTrue(result.coreCollocates().isEmpty());
        assertTrue(result.seedCollocates().isEmpty());
    }

    @Test
    void explore_minSharedOne_includesCollocatesAppearsInAnySeed() throws IOException {
        Map<String, List<QueryResults.WordSketchResult>> data = Map.of(
                "theory", List.of(wsr("unique", 9.0, 5)),
                "model",  List.of(wsr("common", 7.0, 5)));

        MultiSeedExplorer explorer = new MultiSeedExplorer(stubExecutor(data));
        ExplorationResult result = explorer.findCollocateIntersection(
                Set.of("theory", "model"), anyRelation(), 0.0, 50, 1);

        // With minShared=1 every collocate qualifies regardless of overlap
        assertFalse(result.coreCollocates().isEmpty(),
                "With minShared=1 all collocates should appear in core collocates");
    }

    @Test
    void explore_aggregateSeedCollocatesContainsAllCollocates() throws IOException {
        Map<String, List<QueryResults.WordSketchResult>> data = new LinkedHashMap<>();
        data.put("theory", List.of(wsr("important", 8.0, 50)));
        data.put("model",  List.of(wsr("recent",    6.0, 30)));

        MultiSeedExplorer explorer = new MultiSeedExplorer(stubExecutor(data));
        ExplorationResult result = explorer.findCollocateIntersection(
                Set.of("theory", "model"), anyRelation(), 0.0, 50, 1);

        assertTrue(result.seedCollocates().containsKey("important"),
                "Aggregate collocate map should contain \"important\"");
        assertTrue(result.seedCollocates().containsKey("recent"),
                "Aggregate collocate map should contain \"recent\"");
    }
}

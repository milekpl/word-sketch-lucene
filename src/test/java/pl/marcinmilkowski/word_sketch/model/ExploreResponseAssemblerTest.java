package pl.marcinmilkowski.word_sketch.model;

import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.api.ExploreResponseAssembler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExploreResponseAssemblerTest {

    private static ExplorationResult resultWith(
            String seed,
            Map<String, Double> seedCollocates,
            Map<String, Long> seedFreqs,
            List<DiscoveredNoun> nouns,
            List<CoreCollocate> core) {
        return new ExplorationResult(List.of(seed), seedCollocates, seedFreqs, nouns, core);
    }

    private static ExplorationResult resultWith(
            List<String> seeds,
            Map<String, Double> seedCollocates,
            Map<String, Long> seedFreqs,
            List<DiscoveredNoun> nouns,
            List<CoreCollocate> core) {
        return new ExplorationResult(seeds, seedCollocates, seedFreqs, nouns, core);
    }

    @Test
    void buildEdges_fromSeedCollocates_createsEdgesWithSeedAdjType() {
        Map<String, Double> collocates = Map.of("important", 8.5, "novel", 6.0);
        ExplorationResult result = resultWith("theory", collocates, Map.of(), List.of(), List.of());

        List<Edge> edges = ExploreResponseAssembler.buildEdges(result);

        assertEquals(2, edges.size());
        assertTrue(edges.stream().allMatch(e -> e.source().equals("theory")));
        assertTrue(edges.stream().allMatch(e -> e.type() == RelationEdgeType.SEED_ADJ));
        Edge importantEdge = edges.stream()
                .filter(e -> e.target().equals("important")).findFirst().orElseThrow();
        assertEquals(8.5, importantEdge.weight(), 0.001);
    }

    @Test
    void buildEdges_fromDiscoveredNouns_createsDiscoveredAdjEdges() {
        DiscoveredNoun noun = new DiscoveredNoun("model", Map.of("abstract", 7.0), 1, 7.0, 7.0);
        ExplorationResult result = resultWith("theory", Map.of(), Map.of(), List.of(noun), List.of());

        List<Edge> edges = ExploreResponseAssembler.buildEdges(result);

        assertEquals(1, edges.size());
        assertEquals("model", edges.get(0).source());
        assertEquals("abstract", edges.get(0).target());
        assertEquals(RelationEdgeType.DISCOVERED_ADJ, edges.get(0).type());
    }

    @Test
    void buildEdges_emptyResult_returnsEmptyList() {
        ExplorationResult result = ExplorationResult.empty("theory");
        List<Edge> edges = ExploreResponseAssembler.buildEdges(result);
        assertTrue(edges.isEmpty());
    }

    @Test
    void populateExploreResponse_addsAllRequiredKeys() {
        Map<String, Double> collocates = Map.of("important", 8.0);
        Map<String, Long> freqs = Map.of("important", 42L);
        List<DiscoveredNoun> nouns = List.of(
                new DiscoveredNoun("model", Map.of("abstract", 6.0), 1, 6.0, 6.0));
        List<CoreCollocate> core = List.of(new CoreCollocate("important", 2, 2, 8.0, 7.5));
        ExplorationResult result = resultWith("theory", collocates, freqs, nouns, core);

        Map<String, Object> response = new HashMap<>();
        ExploreResponseAssembler.populateExploreResponse(response, result);

        assertTrue(response.containsKey("seed_collocates"), "should have seed_collocates");
        assertTrue(response.containsKey("seed_collocates_count"), "should have seed_collocates_count");
        assertTrue(response.containsKey("discovered_nouns"), "should have discovered_nouns");
        assertTrue(response.containsKey("discovered_nouns_count"), "should have discovered_nouns_count");
        assertTrue(response.containsKey("core_collocates"), "should have core_collocates");
        assertTrue(response.containsKey("core_collocates_count"), "should have core_collocates_count");
        assertTrue(response.containsKey("edges"), "should have edges");
    }

    @Test
    void populateExploreResponse_countsMatchListSizes() {
        Map<String, Double> collocates = Map.of("important", 8.0, "novel", 5.0);
        ExplorationResult result = resultWith("theory", collocates, Map.of(), List.of(), List.of());

        Map<String, Object> response = new HashMap<>();
        ExploreResponseAssembler.populateExploreResponse(response, result);

        @SuppressWarnings("unchecked")
        List<?> seedCollocs = (List<?>) response.get("seed_collocates");
        assertEquals(2, seedCollocs.size());
        assertEquals(2, response.get("seed_collocates_count"));
    }

    @Test
    void formatSeedCollocates_includesWordLogDiceAndFrequency() {
        Map<String, Double> collocates = Map.of("important", 8.75);
        Map<String, Long> freqs = Map.of("important", 100L);
        ExplorationResult result = resultWith("theory", collocates, freqs, List.of(), List.of());

        List<Map<String, Object>> formatted = ExploreResponseAssembler.formatSeedCollocates(result);

        assertEquals(1, formatted.size());
        Map<String, Object> entry = formatted.get(0);
        assertEquals("important", entry.get("word"));
        assertEquals(8.75, (double) entry.get("log_dice"), 0.01);
        assertEquals(100L, entry.get("frequency"));
    }

    @Test
    void formatDiscoveredNouns_includesRequiredFields() {
        DiscoveredNoun noun = new DiscoveredNoun("model", Map.of("abstract", 7.0), 1, 14.0, 7.0);
        ExplorationResult result = resultWith("theory", Map.of(), Map.of(), List.of(noun), List.of());

        List<Map<String, Object>> formatted = ExploreResponseAssembler.formatDiscoveredNouns(result);

        assertEquals(1, formatted.size());
        Map<String, Object> entry = formatted.get(0);
        assertEquals("model", entry.get("word"));
        assertEquals(1, entry.get("shared_count"));
        assertNotNull(entry.get("similarity_score"));
        assertNotNull(entry.get("avg_logdice"));
        assertNotNull(entry.get("shared_collocates"));
    }

    @Test
    void buildEdges_multiSeed_seedAdjEdgesUseIndividualSeedSources() {
        // In multi-seed mode seeds() returns individual lemmas; edges must use them as sources
        Map<String, Double> aggregateCollocates = Map.of("abstract", 7.0);
        ExplorationResult result = resultWith(List.of("theory", "model"), aggregateCollocates, Map.of(), List.of(), List.of());

        List<Edge> edges = ExploreResponseAssembler.buildEdges(result);

        assertEquals(2, edges.size(), "one SEED_ADJ edge per individual seed");
        List<String> sources = edges.stream().map(Edge::source).toList();
        assertTrue(sources.contains("theory"), "edge from 'theory' must exist");
        assertTrue(sources.contains("model"), "edge from 'model' must exist");
        assertFalse(sources.contains("theory,model"), "comma-joined string must not be used as source");
    }

    @Test
    void round2dp_roundsCorrectly() {
        assertEquals(3.14, ExploreResponseAssembler.roundTo2DecimalPlaces(3.14159), 0.001);
        assertEquals(0.0, ExploreResponseAssembler.roundTo2DecimalPlaces(0.0), 0.001);
        assertEquals(14.0, ExploreResponseAssembler.roundTo2DecimalPlaces(14.0), 0.001);
    }

    @Test
    void serializeEdge_includesAllFields() {
        Edge edge = new Edge("theory", "abstract", 8.567, RelationEdgeType.SEED_ADJ);
        Map<String, Object> m = ExploreResponseAssembler.serializeEdge(edge);

        assertEquals("theory", m.get("source"));
        assertEquals("abstract", m.get("target"));
        assertEquals(8.57, (double) m.get("log_dice"), 0.001);
        assertEquals("seed_adj", m.get("type"));
    }
}

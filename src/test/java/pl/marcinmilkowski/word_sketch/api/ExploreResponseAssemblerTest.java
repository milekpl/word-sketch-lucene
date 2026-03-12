package pl.marcinmilkowski.word_sketch.api;

import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.api.model.ExploreResponse;
import pl.marcinmilkowski.word_sketch.api.model.SeedCollocateEntry;
import pl.marcinmilkowski.word_sketch.model.exploration.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.exploration.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.model.exploration.Edge;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.exploration.RelationEdgeType;
import pl.marcinmilkowski.word_sketch.utils.MathUtils;

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
        return ExplorationResult.of(List.of(seed), seedCollocates, seedFreqs, nouns, core, Map.of());
    }

    private static ExplorationResult resultWith(
            List<String> seeds,
            Map<String, Double> seedCollocates,
            Map<String, Long> seedFreqs,
            List<DiscoveredNoun> nouns,
            List<CoreCollocate> core) {
        return ExplorationResult.of(seeds, seedCollocates, seedFreqs, nouns, core, Map.of());
    }

    @Test
    void buildEdges_fromSeedCollocates_createsEdgesWithSeedAdjType() {
        Map<String, Double> collocates = Map.of("important", 8.5, "novel", 6.0);
        Map<String, Map<String, Double>> perSeed = Map.of("theory", collocates);
        ExplorationResult result = ExplorationResult.of(
            List.of("theory"), collocates, Map.of(), List.of(), List.of(), perSeed);

        List<Edge> edges = ExploreResponseAssembler.buildExplorationEdges(result);

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

        List<Edge> edges = ExploreResponseAssembler.buildExplorationEdges(result);

        assertEquals(1, edges.size());
        assertEquals("model", edges.get(0).source());
        assertEquals("abstract", edges.get(0).target());
        assertEquals(RelationEdgeType.DISCOVERED_ADJ, edges.get(0).type());
    }

    @Test
    void buildEdges_emptyResult_returnsEmptyList() {
        ExplorationResult result = ExplorationResult.empty("theory");
        List<Edge> edges = ExploreResponseAssembler.buildExplorationEdges(result);
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

        ExploreResponse response = ExploreResponseAssembler.buildSingleSeedExploreResponse(
                result, "adj_predicate", 10, 1, 0.0, 20);

        assertNotNull(response.seedCollocates(), "should have seed_collocates");
        assertTrue(response.seedCollocatesCount() >= 0, "should have seed_collocates_count");
        assertNotNull(response.discoveredNouns(), "should have discovered_nouns");
        assertTrue(response.discoveredNounsCount() >= 0, "should have discovered_nouns_count");
        assertNotNull(response.coreCollocates(), "should have core_collocates");
        assertTrue(response.coreCollocatesCount() >= 0, "should have core_collocates_count");
        assertNotNull(response.edges(), "should have edges");
    }

    @Test
    void populateExploreResponse_countsMatchListSizes() {
        Map<String, Double> collocates = Map.of("important", 8.0, "novel", 5.0);
        ExplorationResult result = resultWith("theory", collocates, Map.of(), List.of(), List.of());

        ExploreResponse response = ExploreResponseAssembler.buildSingleSeedExploreResponse(
                result, "adj_predicate", 10, 1, 0.0, 20);

        assertEquals(2, response.seedCollocates().size());
        assertEquals(2, response.seedCollocatesCount());
    }

    @Test
    void formatSeedCollocates_includesWordLogDiceAndFrequency() {
        Map<String, Double> collocates = Map.of("important", 8.75);
        Map<String, Long> freqs = Map.of("important", 100L);
        ExplorationResult result = resultWith("theory", collocates, freqs, List.of(), List.of());

        ExploreResponse response = ExploreResponseAssembler.buildSingleSeedExploreResponse(
                result, "adj_predicate", 10, 1, 0.0, 20);

        assertEquals(1, response.seedCollocates().size());
        SeedCollocateEntry entry = response.seedCollocates().get(0);
        assertEquals("important", entry.word());
        assertEquals(8.75, entry.logDice(), 0.01);
        assertEquals(100L, entry.frequency());
    }

    @Test
    void formatDiscoveredNouns_includesRequiredFields() {
        DiscoveredNoun noun = new DiscoveredNoun("model", Map.of("abstract", 7.0), 1, 14.0, 7.0);
        ExplorationResult result = resultWith("theory", Map.of(), Map.of(), List.of(noun), List.of());

        ExploreResponse response = ExploreResponseAssembler.buildSingleSeedExploreResponse(
                result, "adj_predicate", 10, 1, 0.0, 20);

        assertEquals(1, response.discoveredNouns().size());
        ExploreResponse.DiscoveredNounEntry entry = response.discoveredNouns().get(0);
        assertEquals("model", entry.word());
        assertEquals(1, entry.sharedCount());
        assertNotNull(entry.similarityScore());
        assertNotNull(entry.avgLogDice());
        assertNotNull(entry.sharedCollocates());
    }

    @Test
    void buildEdges_multiSeed_seedAdjEdgesUseIndividualSeedSources() {
        // In multi-seed mode perSeedCollocates maps each seed to its own collocates
        Map<String, Double> aggregateCollocates = Map.of("abstract", 7.0);
        Map<String, Map<String, Double>> perSeed = Map.of(
            "theory", Map.of("abstract", 7.0),
            "model",  Map.of("abstract", 7.0));
        ExplorationResult result = ExplorationResult.of(
            List.of("theory", "model"), aggregateCollocates, Map.of(), List.of(), List.of(), perSeed);

        List<Edge> edges = ExploreResponseAssembler.buildExplorationEdges(result);

        assertEquals(2, edges.size(), "one SEED_ADJ edge per individual seed");
        List<String> sources = edges.stream().map(Edge::source).toList();
        assertTrue(sources.contains("theory"), "edge from 'theory' must exist");
        assertTrue(sources.contains("model"), "edge from 'model' must exist");
        assertFalse(sources.contains("theory,model"), "comma-joined string must not be used as source");
    }

    @Test
    void round2dp_roundsCorrectly() {
        assertEquals(3.14, MathUtils.round2dp(3.14159), 0.001);
        assertEquals(0.0, MathUtils.round2dp(0.0), 0.001);
        assertEquals(14.0, MathUtils.round2dp(14.0), 0.001);
    }
}

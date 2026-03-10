package pl.marcinmilkowski.word_sketch.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Result of semantic field exploration from a seed word.
 */
public class ExplorationResult {
    public final String seed;
    public final Map<String, Double> seedCollocates;  // collocate -> logDice with seed
    public final Map<String, Long> seedCollocateFrequencies;  // collocate -> raw frequency
    public final List<DiscoveredNoun> discoveredNouns;
    public final List<CoreCollocate> coreCollocates;

    public ExplorationResult(String seed, Map<String, Double> seedCollocates,
            Map<String, Long> seedCollocateFrequencies,
            List<DiscoveredNoun> discoveredNouns, List<CoreCollocate> coreCollocates) {
        this.seed = seed;
        this.seedCollocates = seedCollocates;
        this.seedCollocateFrequencies = seedCollocateFrequencies;
        this.discoveredNouns = discoveredNouns;
        this.coreCollocates = coreCollocates;
    }

    public static ExplorationResult empty(String seed) {
        return new ExplorationResult(seed, Map.of(), Map.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return discoveredNouns.isEmpty();
    }

    /** Get top N similar nouns */
    public List<DiscoveredNoun> getTopNouns(int n) {
        return discoveredNouns.stream().limit(n).collect(Collectors.toList());
    }

    /** Get nouns sharing at least minShared collocates with seed */
    public List<DiscoveredNoun> getNounsWithMinShared(int minShared) {
        return discoveredNouns.stream()
            .filter(n -> n.sharedCount >= minShared)
            .collect(Collectors.toList());
    }

    /** Build edges for visualization */
    public List<Edge> getEdges() {
        List<Edge> edges = new ArrayList<>();

        // Edges from seed to its collocates
        for (Map.Entry<String, Double> colloc : seedCollocates.entrySet()) {
            edges.add(new Edge(seed, colloc.getKey(), colloc.getValue(), "seed_adj"));
        }

        // Edges from discovered nouns to shared collocates
        for (DiscoveredNoun noun : discoveredNouns) {
            for (Map.Entry<String, Double> colloc : noun.sharedCollocates.entrySet()) {
                edges.add(new Edge(noun.noun, colloc.getKey(), colloc.getValue(), "discovered_adj"));
            }
        }

        return edges;
    }

    @Override
    public String toString() {
        return String.format("ExplorationResult(seed='%s', collocates=%d, discovered=%d, core=%d)",
            seed, seedCollocates.size(), discoveredNouns.size(), coreCollocates.size());
    }
}

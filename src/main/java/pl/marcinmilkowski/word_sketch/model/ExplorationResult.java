package pl.marcinmilkowski.word_sketch.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Result of semantic field exploration from a seed word.
 * <p>
 * This is a traditional class (not a record), so accessor methods intentionally
 * use the {@code get} prefix following standard JavaBeans conventions.
 * Record types in this package (e.g. {@link DiscoveredNoun}, {@link CoreCollocate})
 * omit the {@code get} prefix per Java record conventions.  This class uses
 * traditional fields and getters rather than a record because it is built
 * incrementally and its shape may grow over time.
 * </p>
 */
public class ExplorationResult {
    private final String seed;
    private final Map<String, Double> seedCollocates;  // collocate -> logDice with seed
    private final Map<String, Long> seedCollocateFrequencies;  // collocate -> raw frequency
    private final List<DiscoveredNoun> discoveredNouns;
    private final List<CoreCollocate> coreCollocates;

    public ExplorationResult(String seed, Map<String, Double> seedCollocates,
            Map<String, Long> seedCollocateFrequencies,
            List<DiscoveredNoun> discoveredNouns, List<CoreCollocate> coreCollocates) {
        this.seed = seed;
        this.seedCollocates = seedCollocates;
        this.seedCollocateFrequencies = seedCollocateFrequencies;
        this.discoveredNouns = discoveredNouns;
        this.coreCollocates = coreCollocates;
    }

    public String getSeed() { return seed; }
    public Map<String, Double> getSeedCollocates() { return seedCollocates; }
    public Map<String, Long> getSeedCollocateFrequencies() { return seedCollocateFrequencies; }
    public List<DiscoveredNoun> getDiscoveredNouns() { return discoveredNouns; }
    public List<CoreCollocate> getCoreCollocates() { return coreCollocates; }

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
            .filter(n -> n.sharedCount() >= minShared)
            .collect(Collectors.toList());
    }

    /** Build edges for visualization — performs list construction on every call. */
    public List<Edge> buildEdges() {
        List<Edge> edges = new ArrayList<>();

        // Edges from seed to its collocates
        for (Map.Entry<String, Double> colloc : seedCollocates.entrySet()) {
            edges.add(new Edge(seed, colloc.getKey(), colloc.getValue(), RelationEdgeType.SEED_ADJ));
        }

        // Edges from discovered nouns to shared collocates
        for (DiscoveredNoun noun : discoveredNouns) {
            for (Map.Entry<String, Double> colloc : noun.sharedCollocates().entrySet()) {
                edges.add(new Edge(noun.noun(), colloc.getKey(), colloc.getValue(), RelationEdgeType.DISCOVERED_ADJ));
            }
        }

        return edges;
    }

    /** @deprecated Use {@link #buildEdges()} — name reflects that list construction happens on every call. */
    @Deprecated
    public List<Edge> getEdges() {
        return buildEdges();
    }

    @Override
    public String toString() {
        return String.format("ExplorationResult(seed='%s', collocates=%d, discovered=%d, core=%d)",
            seed, seedCollocates.size(), discoveredNouns.size(), coreCollocates.size());
    }

    /**
     * Serialize this result to a plain map suitable for JSON serialization.
     * Keeps serialization logic co-located with the data it describes.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("seed", seed);

        List<Map<String, Object>> collocList = new ArrayList<>();
        for (Map.Entry<String, Double> e : seedCollocates.entrySet()) {
            Map<String, Object> c = new HashMap<>();
            c.put("word", e.getKey());
            c.put("log_dice", Math.round(e.getValue() * 100.0) / 100.0);
            c.put("frequency", seedCollocateFrequencies.getOrDefault(e.getKey(), 0L));
            collocList.add(c);
        }
        map.put("seed_collocates", collocList);

        List<Map<String, Object>> nounList = new ArrayList<>();
        for (DiscoveredNoun n : discoveredNouns) {
            Map<String, Object> nm = new HashMap<>();
            nm.put("word", n.noun());
            nm.put("shared_count", n.sharedCount());
            nm.put("similarity_score", Math.round(n.sharedCollocateScore() * 100.0) / 100.0);
            nm.put("avg_logdice", Math.round(n.avgLogDice() * 100.0) / 100.0);
            nm.put("shared_collocates", n.sharedCollocateList());
            nounList.add(nm);
        }
        map.put("discovered_nouns", nounList);

        List<Map<String, Object>> coreList = new ArrayList<>();
        for (CoreCollocate c : coreCollocates) {
            Map<String, Object> cm = new HashMap<>();
            cm.put("word", c.collocate());
            cm.put("shared_by_count", c.sharedByCount());
            cm.put("total_nouns", c.totalNouns());
            cm.put("coverage", Math.round(c.coverage() * 100.0) / 100.0);
            cm.put("seed_logdice", Math.round(c.seedLogDice() * 100.0) / 100.0);
            coreList.add(cm);
        }
        map.put("core_collocates", coreList);

        List<Map<String, Object>> edgeList = new ArrayList<>();
        for (Edge edge : buildEdges()) {
            Map<String, Object> em = new HashMap<>();
            em.put("source", edge.source());
            em.put("target", edge.target());
            em.put("log_dice", Math.round(edge.weight() * 100.0) / 100.0);
            em.put("type", edge.type().label());
            edgeList.add(em);
        }
        map.put("edges", edgeList);
        return map;
    }
}

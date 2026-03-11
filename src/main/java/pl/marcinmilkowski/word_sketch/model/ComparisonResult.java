package pl.marcinmilkowski.word_sketch.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Complete comparison result with graded adjective profiles.
 * <p>
 * This is a traditional class (not a record), so accessor methods intentionally
 * use the {@code get} prefix following standard JavaBeans conventions.
 * Record types in this package (e.g. {@link AdjectiveProfile}, {@link CoreCollocate},
 * {@link DiscoveredNoun}) omit the {@code get} prefix per Java record conventions.
 * </p>
 */
public class ComparisonResult {
    private final List<String> nouns;
    private final List<AdjectiveProfile> adjectives;

    public ComparisonResult(List<String> nouns, List<AdjectiveProfile> adjectives) {
        this.nouns = nouns;
        this.adjectives = adjectives;
    }

    public static ComparisonResult empty() {
        return new ComparisonResult(List.of(), List.of());
    }

    public List<String> getNouns() { return nouns; }
    public List<AdjectiveProfile> getAllAdjectives() { return adjectives; }

    /** Adjectives shared by ALL nouns */
    public List<AdjectiveProfile> getFullyShared() {
        return adjectives.stream()
            .filter(a -> a.presentInCount() == nouns.size())
            .collect(Collectors.toList());
    }

    /** Adjectives shared by 2+ nouns but not all */
    public List<AdjectiveProfile> getPartiallyShared() {
        return adjectives.stream()
            .filter(a -> a.presentInCount() >= 2 && a.presentInCount() < nouns.size())
            .collect(Collectors.toList());
    }

    /** Adjectives specific to exactly one noun */
    public List<AdjectiveProfile> getSpecific() {
        return adjectives.stream()
            .filter(a -> a.presentInCount() == 1)
            .collect(Collectors.toList());
    }

    /** Get adjectives specific to a particular noun */
    public List<AdjectiveProfile> getSpecificTo(String noun) {
        return adjectives.stream()
            .filter(a -> a.presentInCount() == 1 && a.nounScores().getOrDefault(noun, 0.0) > 0)
            .sorted((x, y) -> Double.compare(y.maxLogDice(), x.maxLogDice()))
            .collect(Collectors.toList());
    }

    /** Build edges for visualization — performs list construction on every call. */
    public List<Edge> buildEdges() {
        List<Edge> edges = new ArrayList<>();
        for (AdjectiveProfile adj : adjectives) {
            for (Map.Entry<String, Double> entry : adj.nounScores().entrySet()) {
                if (entry.getValue() > 0) {
                    edges.add(new Edge(adj.adjective(), entry.getKey(),
                        entry.getValue(), RelationEdgeType.MODIFIER));
                }
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
        int shared = (int) adjectives.stream().filter(a -> a.presentInCount() >= 2).count();
        int specific = (int) adjectives.stream().filter(a -> a.presentInCount() == 1).count();
        return String.format("ComparisonResult(%d nouns, %d adjectives: %d shared, %d specific)",
            nouns.size(), adjectives.size(), shared, specific);
    }

    /**
     * Serialize this result to a plain map suitable for JSON serialization.
     * Keeps serialization logic co-located with the data it describes.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("nouns", new ArrayList<>(nouns));

        List<Map<String, Object>> adjList = new ArrayList<>();
        for (AdjectiveProfile adj : adjectives) {
            Map<String, Object> am = new HashMap<>();
            am.put("adjective", adj.adjective());
            am.put("present_in_count", adj.presentInCount());
            am.put("max_log_dice", Math.round(adj.maxLogDice() * 100.0) / 100.0);
            am.put("noun_scores", adj.nounScores());
            adjList.add(am);
        }
        map.put("adjectives", adjList);

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

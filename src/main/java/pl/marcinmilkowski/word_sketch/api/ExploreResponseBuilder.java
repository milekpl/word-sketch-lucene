package pl.marcinmilkowski.word_sketch.api;

import pl.marcinmilkowski.word_sketch.model.AdjectiveProfile;
import pl.marcinmilkowski.word_sketch.model.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.model.Edge;
import pl.marcinmilkowski.word_sketch.model.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.RelationEdgeType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts model-layer result objects into graph {@link Edge} lists and JSON-ready maps for API responses.
 *
 * <p>This class owns the model-to-presentation translation so that {@link ExplorationResult}
 * and {@link ComparisonResult} stay free of presentation concerns. All response-body assembly
 * for exploration endpoints is centralised here.</p>
 */
final class ExploreResponseBuilder {

    private ExploreResponseBuilder() {}

    /** Builds {@link RelationEdgeType#SEED_ADJ} edges from seed collocates and {@link RelationEdgeType#DISCOVERED_ADJ} edges from each discovered noun's shared collocates. */
    static List<Edge> buildEdges(ExplorationResult result) {
        List<Edge> edges = new ArrayList<>();
        for (Map.Entry<String, Double> colloc : result.getSeedCollocates().entrySet()) {
            edges.add(new Edge(result.getSeed(), colloc.getKey(), colloc.getValue(), RelationEdgeType.SEED_ADJ));
        }
        for (DiscoveredNoun noun : result.getDiscoveredNouns()) {
            for (Map.Entry<String, Double> colloc : noun.sharedCollocates().entrySet()) {
                edges.add(new Edge(noun.noun(), colloc.getKey(), colloc.getValue(), RelationEdgeType.DISCOVERED_ADJ));
            }
        }
        return edges;
    }

    /** Builds {@link RelationEdgeType#MODIFIER} edges for adjective-noun pairs with positive logDice scores. */
    static List<Edge> buildEdges(ComparisonResult result) {
        List<Edge> edges = new ArrayList<>();
        for (AdjectiveProfile adj : result.getAllAdjectives()) {
            for (Map.Entry<String, Double> entry : adj.nounScores().entrySet()) {
                if (entry.getValue() > 0) {
                    edges.add(new Edge(adj.adjective(), entry.getKey(), entry.getValue(), RelationEdgeType.MODIFIER));
                }
            }
        }
        return edges;
    }

    /**
     * Populates {@code response} with all exploration result fields:
     * {@code seed_collocates}, {@code discovered_nouns}, {@code core_collocates}, and {@code edges}.
     * Centralises response assembly so {@link ExplorationHandlers} only handles HTTP concerns.
     */
    static void populateExploreResponse(Map<String, Object> response, ExplorationResult result) {
        List<Map<String, Object>> seedCollocs = formatSeedCollocates(result);
        response.put("seed_collocates", seedCollocs);
        response.put("seed_collocates_count", seedCollocs.size());

        List<Map<String, Object>> nouns = formatDiscoveredNouns(result);
        response.put("discovered_nouns", nouns);
        response.put("discovered_nouns_count", nouns.size());

        List<Map<String, Object>> coreCollocs = formatCoreCollocates(result);
        response.put("core_collocates", coreCollocs);
        response.put("core_collocates_count", coreCollocs.size());

        List<Edge> edges = buildEdges(result);
        response.put("edges", edges.stream().map(Edge::toMap).toList());
    }

    static List<Map<String, Object>> formatSeedCollocates(ExplorationResult result) {
        List<Map<String, Object>> seedCollocs = new ArrayList<>();
        for (Map.Entry<String, Double> e : result.getSeedCollocates().entrySet()) {
            Map<String, Object> c = new HashMap<>();
            c.put("word", e.getKey());
            c.put("log_dice", round2dp(e.getValue()));
            c.put("frequency", result.getSeedCollocateFrequencies().getOrDefault(e.getKey(), 0L));
            seedCollocs.add(c);
        }
        return seedCollocs;
    }

    static List<Map<String, Object>> formatDiscoveredNouns(ExplorationResult result) {
        List<Map<String, Object>> nouns = new ArrayList<>();
        for (DiscoveredNoun n : result.getDiscoveredNouns()) {
            Map<String, Object> nm = new HashMap<>();
            nm.put("word", n.noun());
            nm.put("shared_count", n.sharedCount());
            nm.put("similarity_score", round2dp(n.combinedRelevanceScore()));
            nm.put("avg_logdice", round2dp(n.avgLogDice()));
            nm.put("shared_collocates", n.sharedCollocateList());
            nouns.add(nm);
        }
        return nouns;
    }

    static List<Map<String, Object>> formatCoreCollocates(ExplorationResult result) {
        List<Map<String, Object>> coreCollocs = new ArrayList<>();
        for (CoreCollocate c : result.getCoreCollocates()) {
            Map<String, Object> cm = new HashMap<>();
            cm.put("word", c.collocate());
            cm.put("shared_by_count", c.sharedByCount());
            cm.put("total_nouns", c.totalNouns());
            cm.put("coverage", round2dp(c.coverage()));
            cm.put("seed_logdice", round2dp(c.seedLogDice()));
            coreCollocs.add(cm);
        }
        return coreCollocs;
    }

    /**
     * Serialises a single {@link AdjectiveProfile} into the JSON-compatible map that the
     * {@code /api/semantic-field} endpoint returns for each adjective entry.
     */
    static Map<String, Object> formatAdjectiveProfile(AdjectiveProfile adj) {
        Map<String, Object> adjMap = new HashMap<>();
        adjMap.put("word", adj.adjective());
        adjMap.put("present_in", adj.presentInCount());
        adjMap.put("total_nouns", adj.totalNouns());
        adjMap.put("avg_logdice", round2dp(adj.avgLogDice()));
        adjMap.put("max_logdice", round2dp(adj.maxLogDice()));
        adjMap.put("variance", round2dp(adj.variance()));
        adjMap.put("commonality_score", round2dp(adj.commonalityScore()));
        adjMap.put("distinctiveness_score", round2dp(adj.distinctivenessScore()));

        String category = adj.isFullyShared() ? "fully_shared"
            : adj.isPartiallyShared() ? "partially_shared" : "specific";
        adjMap.put("category", category);

        Map<String, Double> scores = new HashMap<>();
        for (Map.Entry<String, Double> entry : adj.nounScores().entrySet()) {
            scores.put(entry.getKey(), round2dp(entry.getValue()));
        }
        adjMap.put("noun_scores", scores);

        if (adj.isSpecific()) {
            adj.strongestNoun().ifPresent(n -> adjMap.put("specific_to", n));
        }
        return adjMap;
    }

    /**
     * Populates {@code response} with all comparison result fields:
     * {@code seeds}, {@code seed_count}, {@code parameters}, {@code adjectives},
     * {@code adjectives_count}, {@code fully_shared_count}, {@code partially_shared_count},
     * {@code specific_count}, {@code edges}, and {@code edges_count}.
     */
    static void populateComparisonResponse(Map<String, Object> response, ComparisonResult result,
            Set<String> seeds, int topCollocates, double minLogDice) {
        response.put("status", "ok");
        response.put("seeds", new java.util.ArrayList<>(result.getNouns()));
        response.put("seed_count", seeds.size());

        Map<String, Object> paramsUsed = new HashMap<>();
        paramsUsed.put("top", topCollocates);
        paramsUsed.put("min_logdice", minLogDice);
        response.put("parameters", paramsUsed);

        java.util.List<Map<String, Object>> adjectives = new java.util.ArrayList<>();
        for (AdjectiveProfile adj : result.getAllAdjectives()) {
            adjectives.add(formatAdjectiveProfile(adj));
        }
        response.put("adjectives", adjectives);
        response.put("adjectives_count", result.getAllAdjectives().size());

        ComparisonResult.SummaryCounts counts = result.getSummaryCounts();
        response.put("fully_shared_count", counts.fullyShared());
        response.put("partially_shared_count", counts.partiallyShared());
        response.put("specific_count", counts.specific());

        List<Edge> edges = buildEdges(result);
        response.put("edges", edges.stream().map(Edge::toMap).toList());
        response.put("edges_count", edges.size());
    }

    static double round2dp(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

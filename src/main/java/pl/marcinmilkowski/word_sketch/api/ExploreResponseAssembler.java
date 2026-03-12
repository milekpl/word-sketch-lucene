package pl.marcinmilkowski.word_sketch.api;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.model.exploration.CollocateProfile;
import pl.marcinmilkowski.word_sketch.utils.MathUtils;
import pl.marcinmilkowski.word_sketch.model.exploration.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.exploration.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.exploration.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.model.exploration.Edge;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.exploration.RelationEdgeType;

/**
 * Converts model-layer result objects into graph {@link Edge} lists and JSON-ready maps for API responses.
 *
 * <p>This class owns the model-to-presentation translation so that {@link ExplorationResult}
 * and {@link ComparisonResult} stay free of presentation concerns. All response-body assembly
 * for exploration endpoints is centralised here.</p>
 */
final class ExploreResponseAssembler {

    private ExploreResponseAssembler() {}

    /**
     * Builds {@link RelationEdgeType#SEED_COLLOCATE} edges from seed collocates and
     * {@link RelationEdgeType#DISCOVERED_COLLOCATE} edges from each discovered noun's shared collocates.
     *
     * <p>In multi-seed mode {@code result.seeds()} returns the individual seed lemmas, so each
     * {@code SEED_COLLOCATE} edge correctly names its source (e.g. "theory" or "model") rather than
     * the comma-joined aggregate string that {@link ExplorationResult#seed()} would return.</p>
     */
    public static @NonNull List<Edge> buildExplorationEdges(@NonNull ExplorationResult result) {
        List<Edge> edges = new ArrayList<>();
        Map<String, Map<String, Double>> perSeed = result.perSeedCollocates();
        for (Map.Entry<String, Map<String, Double>> seedEntry : perSeed.entrySet()) {
            for (Map.Entry<String, Double> colloc : seedEntry.getValue().entrySet()) {
                edges.add(new Edge(seedEntry.getKey(), colloc.getKey(), colloc.getValue(), RelationEdgeType.SEED_COLLOCATE));
            }
        }
        for (DiscoveredNoun noun : result.discoveredNouns()) {
            for (Map.Entry<String, Double> colloc : noun.sharedCollocates().entrySet()) {
                edges.add(new Edge(noun.noun(), colloc.getKey(), colloc.getValue(), RelationEdgeType.DISCOVERED_COLLOCATE));
            }
        }
        return edges;
    }

    /** Builds {@link RelationEdgeType#MODIFIER} edges for collocate-noun pairs with positive logDice scores. */
    public static @NonNull List<Edge> buildComparisonEdges(@NonNull ComparisonResult result) {
        List<Edge> edges = new ArrayList<>();
        for (CollocateProfile adj : result.collocates()) {
            for (Map.Entry<String, Double> entry : adj.nounScores().entrySet()) {
                if (entry.getValue() > 0) {
                    edges.add(new Edge(adj.collocate(), entry.getKey(), entry.getValue(), RelationEdgeType.MODIFIER));
                }
            }
        }
        return edges;
    }

    /**
     * Populates {@code response} with all exploration result fields:
     * {@code seed_collocates}, {@code discovered_nouns}, {@code core_collocates}, and {@code edges}.
     * Centralises response assembly so HTTP handler classes only handle HTTP concerns.
     */
    public static void populateExploreResponse(@NonNull Map<String, Object> response, @NonNull ExplorationResult result) {
        List<Map<String, Object>> seedCollocs = formatSeedCollocates(result);
        response.put("seed_collocates", seedCollocs);
        response.put("seed_collocates_count", seedCollocs.size());

        List<Map<String, Object>> nouns = formatDiscoveredNouns(result);
        response.put("discovered_nouns", nouns);
        response.put("discovered_nouns_count", nouns.size());

        List<Map<String, Object>> coreCollocs = formatCoreCollocates(result);
        response.put("core_collocates", coreCollocs);
        response.put("core_collocates_count", coreCollocs.size());

        List<Edge> edges = buildExplorationEdges(result);
        response.put("edges", edges.stream().map(ExploreResponseAssembler::serializeEdge).toList());
    }

    public static @NonNull List<Map<String, Object>> formatSeedCollocates(@NonNull ExplorationResult result) {
        List<Map<String, Object>> seedCollocs = new ArrayList<>();
        for (Map.Entry<String, Double> e : result.seedCollocates().entrySet()) {
            Map<String, Object> c = new HashMap<>();
            c.put("word", e.getKey());
            c.put("log_dice", MathUtils.round2dp(e.getValue()));
            c.put("frequency", result.seedCollocateFrequencies().getOrDefault(e.getKey(), 0L));
            seedCollocs.add(c);
        }
        return seedCollocs;
    }

    public static @NonNull List<Map<String, Object>> formatDiscoveredNouns(@NonNull ExplorationResult result) {
        List<Map<String, Object>> nouns = new ArrayList<>();
        for (DiscoveredNoun n : result.discoveredNouns()) {
            Map<String, Object> nm = new HashMap<>();
            nm.put("word", n.noun());
            nm.put("shared_count", n.sharedCount());
            nm.put("similarity_score", MathUtils.round2dp(n.combinedRelevanceScore()));
            nm.put("avg_logdice", MathUtils.round2dp(n.avgLogDice()));
            nm.put("shared_collocates", n.sharedCollocateList());
            nouns.add(nm);
        }
        return nouns;
    }

    public static @NonNull List<Map<String, Object>> formatCoreCollocates(@NonNull ExplorationResult result) {
        List<Map<String, Object>> coreCollocs = new ArrayList<>();
        for (CoreCollocate c : result.coreCollocates()) {
            Map<String, Object> cm = new HashMap<>();
            cm.put("word", c.collocate());
            cm.put("shared_by_count", c.sharedByCount());
            cm.put("total_nouns", c.totalNouns());
            cm.put("coverage", MathUtils.round2dp(c.coverage()));
            cm.put("seed_logdice", MathUtils.round2dp(c.seedLogDice()));
            coreCollocs.add(cm);
        }
        return coreCollocs;
    }

    /**
     * Serialises a single {@link CollocateProfile} into the JSON-compatible map that the
     * {@code /api/semantic-field} endpoint returns for each collocate entry.
     */
    public static @NonNull Map<String, Object> formatCollocateProfile(@NonNull CollocateProfile adj) {
        Map<String, Object> adjMap = new HashMap<>();
        adjMap.put("word", adj.collocate());
        adjMap.put("present_in", adj.presentInCount());
        adjMap.put("total_nouns", adj.totalNouns());
        adjMap.put("avg_logdice", MathUtils.round2dp(adj.avgLogDice()));
        adjMap.put("max_logdice", MathUtils.round2dp(adj.maxLogDice()));
        adjMap.put("variance", MathUtils.round2dp(adj.variance()));
        adjMap.put("commonality_score", MathUtils.round2dp(adj.commonalityScore()));
        adjMap.put("distinctiveness_score", MathUtils.round2dp(adj.distinctivenessScore()));

        adjMap.put("category", adj.sharingCategory().label());

        Map<String, Double> scores = new HashMap<>();
        for (Map.Entry<String, Double> entry : adj.nounScores().entrySet()) {
            scores.put(entry.getKey(), MathUtils.round2dp(entry.getValue()));
        }
        adjMap.put("noun_scores", scores);

        if (adj.isSpecific()) {
            adj.strongestNoun().ifPresent(n -> adjMap.put("specific_to", n));
        }
        return adjMap;
    }

    /**
     * Populates {@code response} with comparison data fields:
     * {@code adjectives}, {@code adjectives_count}, {@code fully_shared_count},
     * {@code partially_shared_count}, {@code specific_count}, {@code edges}, and {@code edges_count}.
     *
     * <p>Envelope fields ({@code status}, {@code seeds}, {@code seed_count}, {@code parameters})
     * are the caller's responsibility, keeping this method symmetric with
     * {@link #populateExploreResponse}.</p>
     */
    public static void populateComparisonResponse(@NonNull Map<String, Object> response, @NonNull ComparisonResult result) {
        java.util.List<Map<String, Object>> adjectives = new java.util.ArrayList<>();
        for (CollocateProfile adj : result.collocates()) {
            adjectives.add(formatCollocateProfile(adj));
        }
        response.put("adjectives", adjectives);
        response.put("adjectives_count", result.collocates().size());

        ComparisonResult.SummaryCounts counts = result.summaryCounts();
        response.put("fully_shared_count", counts.fullyShared());
        response.put("partially_shared_count", counts.partiallyShared());
        response.put("specific_count", counts.specific());

        List<Edge> edges = buildComparisonEdges(result);
        response.put("edges", edges.stream().map(ExploreResponseAssembler::serializeEdge).toList());
        response.put("edges_count", edges.size());
    }

    /**
     * Serialises a {@link QueryResults.CollocateResult} to the compact "examples" projection:
     * {@code sentence} and {@code raw} only. Use this when the caller needs concordance context
     * but not scoring metadata (e.g., the concordance-examples endpoint).
     */
    public static @NonNull Map<String, Object> collocateToExampleMap(QueryResults.CollocateResult r) {
        Map<String, Object> m = new HashMap<>();
        m.put("sentence", r.sentence());
        m.put("raw", r.rawXml() != null ? r.rawXml() : "");
        return m;
    }

    /**
     * Serialises a {@link QueryResults.CollocateResult} to the full projection: all scored
     * fields including {@code match_start}, {@code match_end}, {@code collocate_lemma},
     * {@code frequency}, and {@code log_dice}. Use for endpoints that expose full scoring data
     * (e.g., the BCQL query endpoint).
     */
    public static @NonNull Map<String, Object> collocateToFullResultMap(QueryResults.CollocateResult r) {
        Map<String, Object> m = new HashMap<>();
        m.put("sentence", r.sentence());
        m.put("raw", r.rawXml() != null ? r.rawXml() : "");
        m.put("match_start", r.startOffset());
        m.put("match_end", r.endOffset());
        m.put("collocate_lemma", r.collocateLemma() != null ? r.collocateLemma() : "");
        m.put("frequency", r.frequency());
        m.put("log_dice", r.logDice());
        return m;
    }

    /**
     * Serialises an {@link Edge} to a plain map for JSON output.
     * The {@code log_dice} field is rounded to two decimal places.
     *
     * @return mutable map with keys {@code source}, {@code target}, {@code log_dice}, {@code type}
     */
    public static @NonNull Map<String, Object> serializeEdge(@NonNull Edge edge) {
        Map<String, Object> m = new HashMap<>();
        m.put("source", edge.source());
        m.put("target", edge.target());
        m.put("log_dice", MathUtils.round2dp(edge.weight()));
        m.put("type", edge.type().label());
        return m;
    }
}

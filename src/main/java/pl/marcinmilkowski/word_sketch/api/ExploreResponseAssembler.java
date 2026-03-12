package pl.marcinmilkowski.word_sketch.api;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pl.marcinmilkowski.word_sketch.api.model.ExploreResponse;
import pl.marcinmilkowski.word_sketch.api.model.SeedCollocateEntry;
import pl.marcinmilkowski.word_sketch.model.sketch.*;
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
     * Builds {@link RelationEdgeType#SEED_ADJ} edges from seed collocates and
     * {@link RelationEdgeType#DISCOVERED_ADJ} edges from each discovered noun's shared collocates.
     *
     * <p>In multi-seed mode {@code result.seeds()} returns the individual seed lemmas, so each
     * {@code SEED_ADJ} edge correctly names its source (e.g. "theory" or "model") rather than
     * the comma-joined aggregate string that {@link ExplorationResult#seed()} would return.</p>
     */
    static @NonNull List<Edge> buildExplorationEdges(@NonNull ExplorationResult result) {
        List<Edge> edges = new ArrayList<>();
        Map<String, Map<String, Double>> perSeed = result.perSeedCollocates();
        for (Map.Entry<String, Map<String, Double>> seedEntry : perSeed.entrySet()) {
            for (Map.Entry<String, Double> colloc : seedEntry.getValue().entrySet()) {
                edges.add(new Edge(seedEntry.getKey(), colloc.getKey(), colloc.getValue(), RelationEdgeType.SEED_ADJ));
            }
        }
        for (DiscoveredNoun noun : result.discoveredNouns()) {
            for (Map.Entry<String, Double> colloc : noun.sharedCollocates().entrySet()) {
                edges.add(new Edge(noun.noun(), colloc.getKey(), colloc.getValue(), RelationEdgeType.DISCOVERED_ADJ));
            }
        }
        return edges;
    }

    /** Builds {@link RelationEdgeType#MODIFIER} edges for collocate-noun pairs with positive logDice scores. */
    static @NonNull List<Edge> buildComparisonEdges(@NonNull ComparisonResult result) {
        List<Edge> edges = new ArrayList<>();
        for (CollocateProfile collocate : result.collocates()) {
            for (Map.Entry<String, Double> entry : collocate.nounScores().entrySet()) {
                if (entry.getValue() > 0) {
                    edges.add(new Edge(collocate.collocate(), entry.getKey(), entry.getValue(), RelationEdgeType.MODIFIER));
                }
            }
        }
        return edges;
    }

    /**
     * Builds a fully-typed {@link ExploreResponse} for the single-seed explore endpoint
     * ({@code GET /api/semantic-field/explore}).
     *
     * <p>The returned record carries the complete response body including the envelope fields
     * ({@code status}, {@code seed}, {@code parameters}) and the payload fields
     * ({@code seed_collocates}, {@code discovered_nouns}, {@code core_collocates},
     * {@code edges} and their count companions).</p>
     *
     * @param result        exploration result from the service layer
     * @param relationType  resolved relation identifier (e.g. {@code "adj_predicate"})
     * @param top           maximum number of collocates requested
     * @param minShared     minimum shared-by count filter applied
     * @param minLogDice    minimum logDice threshold applied
     * @param nounsPerSeed  maximum discovered nouns per seed
     * @return typed response ready for JSON serialisation
     */
    static @NonNull ExploreResponse buildSingleSeedExploreResponse(
            @NonNull ExplorationResult result,
            @NonNull String relationType,
            int top, int minShared, double minLogDice, int nounsPerSeed) {
        ExploreResponse.Parameters params = new ExploreResponse.Parameters(
                relationType, top, minShared, minLogDice, nounsPerSeed);
        return buildExplorePayload(result, result.seed(), null, null, params);
    }

    /**
     * Builds a fully-typed {@link ExploreResponse} for the multi-seed explore endpoint
     * ({@code GET /api/semantic-field/explore-multi}).
     *
     * <p>The {@code seeds} / {@code seed_count} envelope fields are set and {@code seed} is
     * absent; the {@code nouns_per} parameter field is absent because multi-seed exploration
     * does not support it.</p>
     *
     * @param result       exploration result from the service layer
     * @param relationType resolved relation identifier
     * @param top          maximum number of collocates requested
     * @param minShared    minimum shared-by count filter applied
     * @param minLogDice   minimum logDice threshold applied
     * @return typed response ready for JSON serialisation
     */
    static @NonNull ExploreResponse buildMultiSeedExploreResponse(
            @NonNull ExplorationResult result,
            @NonNull String relationType,
            int top, int minShared, double minLogDice) {
        List<String> seeds = result.seeds();
        ExploreResponse.Parameters params = new ExploreResponse.Parameters(
                relationType, top, minShared, minLogDice, null);
        return buildExplorePayload(result, null, seeds, seeds.size(), params);
    }

    /** Shared payload builder used by both single- and multi-seed factory methods. */
    private static ExploreResponse buildExplorePayload(
            @NonNull ExplorationResult result,
            @Nullable String seed,
            @Nullable List<String> seeds,
            @Nullable Integer seedCount,
            ExploreResponse.Parameters parameters) {

        List<SeedCollocateEntry> seedCollocs = buildSeedCollocateEntries(result);
        List<ExploreResponse.DiscoveredNounEntry> nouns = buildDiscoveredNounEntries(result);
        List<ExploreResponse.CoreCollocateEntry> core = buildCoreCollocateEntries(result);
        List<ExploreResponse.EdgeEntry> edges = buildEdgeEntries(result);

        return new ExploreResponse(
                "ok", seed, seeds, seedCount, parameters,
                seedCollocs, seedCollocs.size(),
                nouns, nouns.size(),
                core, core.size(),
                edges, edges.size());
    }

    // -------------------------------------------------------------------------
    // Typed entry builders (used by the factory methods above)
    // -------------------------------------------------------------------------

    private static @NonNull List<SeedCollocateEntry> buildSeedCollocateEntries(
            @NonNull ExplorationResult result) {
        List<SeedCollocateEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Double> e : result.seedCollocates().entrySet()) {
            long freq = result.seedCollocateFrequencies().getOrDefault(e.getKey(), 0L);
            entries.add(new SeedCollocateEntry(e.getKey(), MathUtils.round2dp(e.getValue()), freq));
        }
        return entries;
    }

    private static @NonNull List<ExploreResponse.DiscoveredNounEntry> buildDiscoveredNounEntries(
            @NonNull ExplorationResult result) {
        List<ExploreResponse.DiscoveredNounEntry> entries = new ArrayList<>();
        for (DiscoveredNoun n : result.discoveredNouns()) {
            entries.add(new ExploreResponse.DiscoveredNounEntry(
                    n.noun(),
                    n.sharedCount(),
                    MathUtils.round2dp(n.combinedRelevanceScore()),
                    MathUtils.round2dp(n.avgLogDice()),
                    n.sharedCollocateList()));
        }
        return entries;
    }

    private static @NonNull List<ExploreResponse.CoreCollocateEntry> buildCoreCollocateEntries(
            @NonNull ExplorationResult result) {
        List<ExploreResponse.CoreCollocateEntry> entries = new ArrayList<>();
        for (CoreCollocate c : result.coreCollocates()) {
            entries.add(new ExploreResponse.CoreCollocateEntry(
                    c.collocate(),
                    c.sharedByCount(),
                    c.totalNouns(),
                    MathUtils.round2dp(c.coverage()),
                    MathUtils.round2dp(c.seedLogDice())));
        }
        return entries;
    }

    private static @NonNull List<ExploreResponse.EdgeEntry> buildEdgeEntries(
            @NonNull ExplorationResult result) {
        return buildExplorationEdges(result).stream()
                .map(e -> new ExploreResponse.EdgeEntry(
                        e.source(), e.target(),
                        MathUtils.round2dp(e.weight()),
                        e.type().label()))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Legacy Map-based helper kept for tests and internal callers that still
    // use the Map-based populateExploreResponse path.
    // -------------------------------------------------------------------------

    /**
     * Populates {@code response} with all exploration result fields:
     * {@code seed_collocates}, {@code discovered_nouns}, {@code core_collocates}, and {@code edges}.
     *
     * @deprecated Prefer {@link #buildSingleSeedExploreResponse} or
     *             {@link #buildMultiSeedExploreResponse} which return a typed
     *             {@link ExploreResponse} record instead of mutating a raw map.
     */
    @Deprecated
    static void populateExploreResponse(@NonNull Map<String, Object> response, @NonNull ExplorationResult result) {
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
        response.put("edges", edges.stream().map(ExploreResponseAssembler::edgeToMap).toList());
        response.put("edges_count", edges.size());
    }

    static @NonNull List<Map<String, Object>> formatSeedCollocates(@NonNull ExplorationResult result) {
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

    static @NonNull List<Map<String, Object>> formatDiscoveredNouns(@NonNull ExplorationResult result) {
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

    static @NonNull List<Map<String, Object>> formatCoreCollocates(@NonNull ExplorationResult result) {
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
    static @NonNull Map<String, Object> collocateProfileToMap(@NonNull CollocateProfile collocate) {
        Map<String, Object> collocateMap = new HashMap<>();
        collocateMap.put("word", collocate.collocate());
        collocateMap.put("present_in", collocate.presentInCount());
        collocateMap.put("total_nouns", collocate.totalNouns());
        collocateMap.put("avg_logdice", MathUtils.round2dp(collocate.avgLogDice()));
        collocateMap.put("max_logdice", MathUtils.round2dp(collocate.maxLogDice()));
        collocateMap.put("variance", MathUtils.round2dp(collocate.variance()));
        collocateMap.put("commonality_score", MathUtils.round2dp(collocate.commonalityScore()));
        collocateMap.put("distinctiveness_score", MathUtils.round2dp(collocate.distinctivenessScore()));

        collocateMap.put("category", collocate.sharingCategory().label());

        Map<String, Double> scores = new HashMap<>();
        for (Map.Entry<String, Double> entry : collocate.nounScores().entrySet()) {
            scores.put(entry.getKey(), MathUtils.round2dp(entry.getValue()));
        }
        collocateMap.put("noun_scores", scores);

        if (collocate.isSpecific()) {
            collocate.strongestNoun().ifPresent(n -> collocateMap.put("specific_to", n));
        }
        return collocateMap;
    }

    /**
     * Populates {@code response} with comparison data fields:
     * {@code collocates}, {@code collocates_count}, {@code fully_shared_count},
     * {@code partially_shared_count}, {@code specific_count}, {@code edges}, and {@code edges_count}.
     *
     * <p>Envelope fields ({@code status}, {@code seeds}, {@code seed_count}, {@code parameters})
     * are the caller's responsibility, keeping this method symmetric with
     * {@link #populateExploreResponse}.</p>
     */
    static void populateComparisonResponse(@NonNull Map<String, Object> response, @NonNull ComparisonResult result) {
        List<Map<String, Object>> collocates = new ArrayList<>();
        for (CollocateProfile collocate : result.collocates()) {
            collocates.add(collocateProfileToMap(collocate));
        }
        response.put("collocates", collocates);
        response.put("collocates_count", result.collocates().size());

        ComparisonResult.SummaryCounts counts = result.summaryCounts();
        response.put("fully_shared_count", counts.fullyShared());
        response.put("partially_shared_count", counts.partiallyShared());
        response.put("specific_count", counts.specific());

        List<Edge> edges = buildComparisonEdges(result);
        response.put("edges", edges.stream().map(ExploreResponseAssembler::edgeToMap).toList());
        response.put("edges_count", edges.size());
    }

    /**
     * Serialises a {@link CollocateResult} to the compact "examples" projection:
     * {@code sentence} and {@code raw} only. Use this when the caller needs concordance context
     * but not scoring metadata (e.g., the concordance-examples endpoint).
     */
    static @NonNull Map<String, Object> collocateResultToExampleMap(CollocateResult r) {
        Map<String, Object> m = new HashMap<>();
        m.put("sentence", r.sentence());
        m.put("raw", r.rawXml() != null ? r.rawXml() : "");
        return m;
    }

    /**
     * Converts a {@link CollocateResult} to a typed {@link ExamplesResponse.ExampleEntry}.
     */
    static ExamplesResponse.ExampleEntry collocateResultToExampleEntry(CollocateResult r) {
        return new ExamplesResponse.ExampleEntry(r.sentence(), r.rawXml() != null ? r.rawXml() : "");
    }

    /**
     * Builds a typed {@link ExamplesResponse} for the concordance-examples endpoints.
     *
     * <p>The {@code fallback} flag is set to {@code true} in the response when the named relation
     * was not resolved and a proximity fallback pattern was used; pass {@code null} for normal
     * (non-fallback) responses, in which case the field is omitted from JSON output.</p>
     *
     * @param seed      headword
     * @param collocate collocate word form
     * @param relation  resolved relation identifier
     * @param bcql      the BCQL pattern that was executed
     * @param top       requested maximum result count
     * @param fallback  {@code true} if a fallback pattern was used; {@code null} otherwise
     * @param results   raw query results to convert into example entries
     * @return fully populated {@link ExamplesResponse}
     */
    static @NonNull ExamplesResponse buildExamplesResponse(
            @NonNull String seed, @NonNull String collocate,
            @NonNull String relation, @NonNull String bcql,
            int top, @Nullable Boolean fallback,
            @NonNull List<CollocateResult> results) {
        List<ExamplesResponse.ExampleEntry> entries = results.stream()
                .map(ExploreResponseAssembler::collocateResultToExampleEntry)
                .toList();
        return new ExamplesResponse("ok", seed, collocate, relation, bcql, top, entries.size(), fallback, entries);
    }

    /**
     * Serialises a {@link CollocateResult} to the full projection: all scored
     * fields including {@code match_start}, {@code match_end}, {@code collocate_lemma},
     * {@code frequency}, and {@code log_dice}. Use for endpoints that expose full scoring data
     * (e.g., the BCQL query endpoint).
     */
    static @NonNull Map<String, Object> collocateResultToFullMap(CollocateResult r) {
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
    static @NonNull Map<String, Object> edgeToMap(@NonNull Edge edge) {
        Map<String, Object> m = new HashMap<>();
        m.put("source", edge.source());
        m.put("target", edge.target());
        m.put("log_dice", MathUtils.round2dp(edge.weight()));
        m.put("type", edge.type().label());
        return m;
    }
}

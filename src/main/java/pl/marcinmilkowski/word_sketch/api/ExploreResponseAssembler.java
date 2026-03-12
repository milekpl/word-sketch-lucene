package pl.marcinmilkowski.word_sketch.api;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pl.marcinmilkowski.word_sketch.api.model.ComparisonResponse;
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
     * Builds a typed {@link ComparisonResponse} for the collocate-profile comparison endpoint.
     *
     * @param seeds         the seed nouns used for comparison
     * @param relationType  the relation type identifier
     * @param params        shared exploration parameters
     * @param result        comparison result from the exploration service
     * @return fully populated {@link ComparisonResponse}
     */
    static @NonNull ComparisonResponse buildComparisonResponse(
            @NonNull List<String> seeds, @NonNull String relationType,
            int top, int minShared, double minLogDice,
            @NonNull ComparisonResult result) {
        List<ComparisonResponse.CollocateProfileEntry> collocates = result.collocates().stream()
                .map(ExploreResponseAssembler::collocateProfileToEntry)
                .toList();
        ComparisonResult.SummaryCounts counts = result.summaryCounts();
        List<ComparisonResponse.EdgeEntry> edges = buildComparisonEdges(result).stream()
                .map(e -> new ComparisonResponse.EdgeEntry(e.source(), e.target(), e.weight(), e.type().label()))
                .toList();
        return new ComparisonResponse(
                "ok", seeds, seeds.size(),
                new ComparisonResponse.ComparisonParameters(relationType, top, minShared, minLogDice),
                collocates, collocates.size(),
                counts.fullyShared(), counts.partiallyShared(), counts.specific(),
                edges, edges.size());
    }

    private static ComparisonResponse.CollocateProfileEntry collocateProfileToEntry(
            CollocateProfile collocate) {
        Map<String, Double> nounScores = new HashMap<>();
        for (Map.Entry<String, Double> entry : collocate.nounScores().entrySet()) {
            nounScores.put(entry.getKey(), MathUtils.round2dp(entry.getValue()));
        }
        String specificTo = collocate.isSpecific()
                ? collocate.strongestNoun().orElse(null)
                : null;
        return new ComparisonResponse.CollocateProfileEntry(
                collocate.collocate(),
                collocate.presentInCount(),
                collocate.totalNouns(),
                MathUtils.round2dp(collocate.avgLogDice()),
                MathUtils.round2dp(collocate.maxLogDice()),
                MathUtils.round2dp(collocate.variance()),
                MathUtils.round2dp(collocate.commonalityScore()),
                MathUtils.round2dp(collocate.distinctivenessScore()),
                collocate.sharingCategory().label(),
                nounScores,
                specificTo);
    }

    /**
     * Request context for example-concordance responses.
     *
     * <p>Bundles the metadata fields that describe <em>what was requested</em> — as opposed to
     * the raw {@link CollocateResult} list that describes <em>what was found</em>.  Passing a
     * single context object keeps {@link #buildExamplesResponse} to a manageable arity and makes
     * call sites self-documenting.</p>
     *
     * @param seed      headword lemma
     * @param collocate collocate word form
     * @param relation  resolved relation identifier
     * @param bcql      the BCQL pattern that was executed
     * @param top       requested maximum result count
     * @param fallback  {@code true} if a proximity fallback was used; {@code null} for normal responses
     */
    record ExamplesContext(
            @NonNull String seed,
            @NonNull String collocate,
            @NonNull String relation,
            @NonNull String bcql,
            int top,
            @Nullable Boolean fallback) {}

    /**
     * Converts a {@link CollocateResult} to a typed {@link ExamplesResponse.ExampleEntry}.
     */
    static ExamplesResponse.ExampleEntry collocateResultToExampleEntry(CollocateResult r) {
        return new ExamplesResponse.ExampleEntry(r.sentence(), r.rawXml() != null ? r.rawXml() : "");
    }

    /**
     * Builds a typed {@link ExamplesResponse} for the concordance-examples endpoints.
     *
     * @param ctx     request context (headword, collocate, relation, pattern, top, fallback flag)
     * @param results raw query results to convert into example entries
     * @return fully populated {@link ExamplesResponse}
     */
    static @NonNull ExamplesResponse buildExamplesResponse(
            @NonNull ExamplesContext ctx,
            @NonNull List<CollocateResult> results) {
        List<ExamplesResponse.ExampleEntry> entries = results.stream()
                .map(ExploreResponseAssembler::collocateResultToExampleEntry)
                .toList();
        return new ExamplesResponse("ok", ctx.seed(), ctx.collocate(), ctx.relation(),
                ctx.bcql(), ctx.top(), entries.size(), ctx.fallback(), entries);
    }
}

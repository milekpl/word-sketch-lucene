package pl.marcinmilkowski.word_sketch.exploration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;

import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.config.RelationPatternUtils;
import pl.marcinmilkowski.word_sketch.model.exploration.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.exploration.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

/**
 * Computes multi-seed semantic field exploration: for each seed, fetches collocates using the
 * given relation, then intersects them across seeds to find shared and distinctive patterns.
 *
 * <p>Extracted from {@link SemanticFieldExplorer} so that the single-seed and multi-seed
 * algorithms each have a focused owner. {@link SemanticFieldExplorer} is now a thin facade
 * delegating to both this class and {@link CollocateProfileComparator}.</p>
 */
class MultiSeedExplorer {

    private final QueryExecutor executor;

    public MultiSeedExplorer(QueryExecutor executor) {
        this.executor = executor;
    }

    /**
     * Fetches collocates for each seed using the given relation, then intersects them across
     * seeds to find shared and distinctive patterns.
     *
     * <p>Seeds become the {@code discoveredNouns} (each carrying their common collocates as
     * shared-collocate set); the collocate intersection becomes {@code coreCollocates}; and
     * the aggregate collocate map becomes {@code seedCollocates}.</p>
     */
    @NonNull ExplorationResult findCollocateIntersection(
            @NonNull Set<String> seeds,
            @NonNull RelationConfig relationConfig,
            @NonNull ExplorationOptions opts) throws IOException {

        double minLogDice = opts.minLogDice();
        int topCollocates = opts.topCollocates();
        int minShared = opts.minShared();

        SeedCollocateData data = fetchCollocatesPerSeed(seeds, relationConfig, minLogDice, topCollocates);
        Set<String> commonCollocates = identifyCommonCollocates(data.collocateSharedCount(), minShared, seeds.size());

        AggregatedCollocateStats stats = aggregateCollocateStats(data.seedCollocateMap());
        List<DiscoveredNoun> discoveredNounsList = buildDiscoveredNouns(seeds, data.seedCollocateMap(), commonCollocates);
        List<CoreCollocate> coreCollocatesList = buildCoreCollocates(
                commonCollocates, data.collocateSharedCount(), stats.maxLogDiceByLemma(), stats.avgLogDiceByLemma(), seeds.size());

        Map<String, Map<String, Double>> perSeedCollocates = new LinkedHashMap<>();
        for (Map.Entry<String, List<QueryResults.WordSketchResult>> entry : data.seedCollocateMap().entrySet()) {
            Map<String, Double> collocateMap = new LinkedHashMap<>();
            for (QueryResults.WordSketchResult wordSketchResult : entry.getValue()) {
                collocateMap.put(wordSketchResult.lemma(), wordSketchResult.logDice());
            }
            perSeedCollocates.put(entry.getKey(), collocateMap);
        }

        return ExplorationResult.of(
            new java.util.ArrayList<>(seeds),
            stats.maxLogDiceByLemma(), stats.totalFreqByLemma(),
            discoveredNounsList, coreCollocatesList, perSeedCollocates);
    }

    /**
     * Fetches collocates for each seed using {@code executeSurfacePattern} with the grammar-derived
     * BCQL pattern. No fallback to {@code executeCollocations} is applied.
     *
     * <h3>Query strategy: executeSurfacePattern only (intentionally no fallback)</h3>
     *
     * <p>Multi-seed exploration compares collocate scores across all seeds to identify a shared
     * semantic core. This requires a <em>consistent retrieval method</em> for every seed: if some
     * seeds used {@code executeSurfacePattern} (BCQL, grammatically precise) and others fell back
     * to {@code executeCollocations} (dependency index, less precise), the resulting logDice scores
     * would reflect different retrieval strategies and could not be meaningfully compared.</p>
     *
     * <p>Contrast with {@link SemanticFieldExplorer#fetchSeedCollocates}, which applies the
     * {@code executeCollocations} fallback for single-seed exploration. In that context only
     * one seed is queried, so mixing strategies has no cross-seed comparability cost; the
     * fallback simply rescues rare seeds from returning empty results. That rescue is not
     * appropriate here.</p>
     *
     * <p><b>Design implication:</b> seeds that produce no BCQL results contribute nothing to
     * the intersection. This is correct: if a seed is too infrequent to return structured
     * collocates, it should not artificially inflate or distort the shared collocate set.</p>
     *
     * <p><b>Do not introduce a fallback here</b> unless you also normalise the scores so that
     * BCQL-derived and dependency-index-derived logDice values are on the same scale.</p>
     */
    private SeedCollocateData fetchCollocatesPerSeed(
            Set<String> seeds, RelationConfig relationConfig,
            double minLogDice, int topCollocates) throws IOException {
        Map<String, List<QueryResults.WordSketchResult>> seedCollocateMap = new LinkedHashMap<>();
        Map<String, Integer> collocateSharedCount = new HashMap<>();
        for (String seed : seeds) {
            String bcqlPattern = RelationPatternUtils.buildFullPattern(relationConfig, seed);
            List<QueryResults.WordSketchResult> collocates = executor.executeSurfacePattern(
                bcqlPattern, minLogDice, topCollocates);
            seedCollocateMap.put(seed, collocates);
            for (QueryResults.WordSketchResult wordSketchResult : collocates) {
                collocateSharedCount.merge(wordSketchResult.lemma(), 1, Integer::sum);
            }
        }
        return new SeedCollocateData(seedCollocateMap, collocateSharedCount);
    }

    /** Returns collocates that appear in at least {@code minShared} seeds (capped to seeds.size()). */
    private Set<String> identifyCommonCollocates(
            Map<String, Integer> collocateSharedCount, int minShared, int seedCount) {
        int threshold = Math.min(minShared, seedCount);
        Set<String> commonCollocates = new HashSet<>();
        for (Map.Entry<String, Integer> entry : collocateSharedCount.entrySet()) {
            if (entry.getValue() >= threshold) {
                commonCollocates.add(entry.getKey());
            }
        }
        return commonCollocates;
    }

    /** Builds a {@link DiscoveredNoun} for each seed, capturing its shared-collocate subset. */
    private List<DiscoveredNoun> buildDiscoveredNouns(
            Set<String> seeds,
            Map<String, List<QueryResults.WordSketchResult>> seedCollocateMap,
            Set<String> commonCollocates) {
        List<DiscoveredNoun> discoveredNounsList = new ArrayList<>();
        for (String seed : seeds) {
            List<QueryResults.WordSketchResult> collocs = seedCollocateMap.getOrDefault(seed, List.of());
            Map<String, Double> sharedCollocs = new LinkedHashMap<>();
            for (QueryResults.WordSketchResult wordSketchResult : collocs) {
                if (commonCollocates.contains(wordSketchResult.lemma())) {
                    sharedCollocs.put(wordSketchResult.lemma(), wordSketchResult.logDice());
                }
            }
            int count = sharedCollocs.size();
            java.util.DoubleSummaryStatistics stats = sharedCollocs.values().stream()
                .mapToDouble(Double::doubleValue).summaryStatistics();
            double avg = count == 0 ? 0.0 : stats.getAverage();
            double sum = stats.getSum();
            discoveredNounsList.add(new DiscoveredNoun(seed, sharedCollocs, count, sum, avg));
        }
        return discoveredNounsList;
    }

    /** Builds a {@link CoreCollocate} for each member of the common collocate set. */
    private List<CoreCollocate> buildCoreCollocates(
            Set<String> commonCollocates,
            Map<String, Integer> collocateSharedCount,
            Map<String, Double> maxLogDiceByCollocate,
            Map<String, Double> avgLogDiceMap,
            int numSeeds) {
        List<CoreCollocate> coreCollocatesList = new ArrayList<>();
        for (String collocateLemma : commonCollocates) {
            int sharedBy = collocateSharedCount.getOrDefault(collocateLemma, 0);
            double avgLogDice = avgLogDiceMap.getOrDefault(collocateLemma, 0.0);
            double peakLogDice = maxLogDiceByCollocate.getOrDefault(collocateLemma, 0.0);
            coreCollocatesList.add(new CoreCollocate(collocateLemma, sharedBy, numSeeds, peakLogDice, avgLogDice));
        }
        return coreCollocatesList;
    }

    /**
     * Aggregates per-seed collocate lists into cross-seed statistics:
     * the maximum logDice each collocate achieves across seeds, the sum of its
     * frequencies, and the average of its logDice values.
     */
    private AggregatedCollocateStats aggregateCollocateStats(
            Map<String, List<QueryResults.WordSketchResult>> seedCollocateMap) {
        Map<String, Double> maxLogDice = new LinkedHashMap<>();
        Map<String, Long> totalFreq = new LinkedHashMap<>();
        Map<String, Double> logDiceSum = new HashMap<>();
        Map<String, Integer> logDiceCount = new HashMap<>();
        for (List<QueryResults.WordSketchResult> collocs : seedCollocateMap.values()) {
            for (QueryResults.WordSketchResult wordSketchResult : collocs) {
                maxLogDice.merge(wordSketchResult.lemma(), wordSketchResult.logDice(), Math::max);
                totalFreq.merge(wordSketchResult.lemma(), wordSketchResult.frequency(), Long::sum);
                logDiceSum.merge(wordSketchResult.lemma(), wordSketchResult.logDice(), Double::sum);
                logDiceCount.merge(wordSketchResult.lemma(), 1, Integer::sum);
            }
        }
        Map<String, Double> avgLogDice = new HashMap<>();
        logDiceSum.forEach((lemma, sum) -> avgLogDice.put(lemma, sum / logDiceCount.get(lemma)));
        return new AggregatedCollocateStats(maxLogDice, totalFreq, avgLogDice);
    }

    private record AggregatedCollocateStats(
            Map<String, Double> maxLogDiceByLemma,
            Map<String, Long> totalFreqByLemma,
            Map<String, Double> avgLogDiceByLemma) {}

    private record SeedCollocateData(
            Map<String, List<QueryResults.WordSketchResult>> seedCollocateMap,
            Map<String, Integer> collocateSharedCount) {}
}

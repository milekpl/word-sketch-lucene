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
import pl.marcinmilkowski.word_sketch.config.RelationUtils;
import pl.marcinmilkowski.word_sketch.model.exploration.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.exploration.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.sketch.*;
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

    MultiSeedExplorer(QueryExecutor executor) {
        this.executor = executor;
    }

    /**
     * Fetches collocates for each seed using the given relation, then intersects them across
     * seeds to find shared and distinctive patterns.
     *
     * <p>Seeds populate the {@code source_seeds} field of the API response (each carrying
     * their common collocates as shared-collocate set); the collocate intersection becomes
     * {@code coreCollocates}; and the aggregate collocate map becomes {@code seedCollocates}.</p>
     */
    @NonNull ExplorationResult findCollocateIntersection(
            @NonNull Set<String> seeds,
            @NonNull RelationConfig relationConfig,
            @NonNull ExplorationOptions opts) throws IOException {

        double minLogDice = opts.logDiceThreshold();
        int topCollocates = opts.topCollocates();
        int minShared = opts.minShared();

        SeedCollocateData data = fetchCollocatesPerSeed(seeds, relationConfig, minLogDice, topCollocates);
        Set<String> commonCollocates = identifyCommonCollocates(data.collocateSharedCount(), minShared, seeds.size());

        AggregatedCollocateStats stats = aggregateCollocateStats(data.seedCollocateMap());
        List<DiscoveredNoun> discoveredNounsList = buildDiscoveredNouns(seeds, data.seedCollocateMap(), commonCollocates);
        List<CoreCollocate> coreCollocatesList = buildCoreCollocates(
                commonCollocates, data.collocateSharedCount(), stats.maxLogDiceByLemma(), stats.avgLogDiceByLemma(), seeds.size());

        return ExplorationResult.of(
            new java.util.ArrayList<>(seeds),
            stats.maxLogDiceByLemma(), stats.totalFreqByLemma(),
            discoveredNounsList, coreCollocatesList, data.perSeedCollocates());
    }

    /**
     * Fetches collocates for each seed using {@code executeSurfaceCollocations} with the grammar-derived
     * BCQL pattern. No fallback to {@code executeCollocations} is applied, because multi-seed
     * comparison requires a consistent retrieval method across all seeds for comparable scores.
     */
    private SeedCollocateData fetchCollocatesPerSeed(
            Set<String> seeds, RelationConfig relationConfig,
            double minLogDice, int topCollocates) throws IOException {
        Map<String, List<WordSketchResult>> seedCollocateMap = new LinkedHashMap<>();
        Map<String, Map<String, Double>> perSeedCollocates = new LinkedHashMap<>();
        Map<String, Integer> collocateSharedCount = new LinkedHashMap<>();
        for (String seed : seeds) {
            String bcqlPattern = RelationUtils.buildFullPattern(relationConfig, seed);
            List<WordSketchResult> collocates = executor.executeSurfaceCollocations(
                bcqlPattern, minLogDice, topCollocates);
            seedCollocateMap.put(seed, collocates);
            Map<String, Double> collocateMap = new LinkedHashMap<>();
            for (WordSketchResult sketchResult : collocates) {
                collocateSharedCount.merge(sketchResult.lemma(), 1, Integer::sum);
                collocateMap.put(sketchResult.lemma(), sketchResult.logDice());
            }
            perSeedCollocates.put(seed, collocateMap);
        }
        return new SeedCollocateData(seedCollocateMap, perSeedCollocates, collocateSharedCount);
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
            Map<String, List<WordSketchResult>> seedCollocateMap,
            Set<String> commonCollocates) {
        List<DiscoveredNoun> discoveredNounsList = new ArrayList<>();
        for (String seed : seeds) {
            List<WordSketchResult> collocates = seedCollocateMap.getOrDefault(seed, List.of());
            Map<String, Double> sharedCollocates = new LinkedHashMap<>();
            for (WordSketchResult sketchResult : collocates) {
                if (commonCollocates.contains(sketchResult.lemma())) {
                    sharedCollocates.put(sketchResult.lemma(), sketchResult.logDice());
                }
            }
            int count = sharedCollocates.size();
            java.util.DoubleSummaryStatistics stats = sharedCollocates.values().stream()
                .mapToDouble(Double::doubleValue).summaryStatistics();
            double avg = count == 0 ? 0.0 : stats.getAverage();
            double sum = stats.getSum();
            discoveredNounsList.add(new DiscoveredNoun(seed, sharedCollocates, count, sum, avg));
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
     *
     * <p>Uses a single {@link CollocateAccumulator} per collocate lemma to avoid
     * four separate parallel maps and a second pass to compute averages.</p>
     */
    private AggregatedCollocateStats aggregateCollocateStats(
            Map<String, List<WordSketchResult>> seedCollocateMap) {
        Map<String, CollocateAccumulator> accumulators = new LinkedHashMap<>();
        for (List<WordSketchResult> collocates : seedCollocateMap.values()) {
            for (WordSketchResult sketchResult : collocates) {
                accumulators.computeIfAbsent(sketchResult.lemma(), k -> new CollocateAccumulator())
                            .accumulate(sketchResult.logDice(), sketchResult.frequency());
            }
        }
        Map<String, Double> maxLogDice = new LinkedHashMap<>();
        Map<String, Long> totalFreq = new LinkedHashMap<>();
        Map<String, Double> avgLogDice = new LinkedHashMap<>();
        accumulators.forEach((lemma, acc) -> {
            maxLogDice.put(lemma, acc.maxLogDice);
            totalFreq.put(lemma, acc.totalFreq);
            avgLogDice.put(lemma, acc.avgLogDice());
        });
        return new AggregatedCollocateStats(maxLogDice, totalFreq, avgLogDice);
    }

    /** Mutable accumulator for per-collocate cross-seed statistics. */
    private static final class CollocateAccumulator {
        double maxLogDice;
        long totalFreq;
        double logDiceSum;
        int count;

        void accumulate(double logDice, long freq) {
            if (logDice > maxLogDice) maxLogDice = logDice;
            totalFreq += freq;
            logDiceSum += logDice;
            count++;
        }

        double avgLogDice() {
            return count == 0 ? 0.0 : logDiceSum / count;
        }
    }

    private record AggregatedCollocateStats(
            Map<String, Double> maxLogDiceByLemma,
            Map<String, Long> totalFreqByLemma,
            Map<String, Double> avgLogDiceByLemma) {}

    private record SeedCollocateData(
            Map<String, List<WordSketchResult>> seedCollocateMap,
            Map<String, Map<String, Double>> perSeedCollocates,
            Map<String, Integer> collocateSharedCount) {}
}

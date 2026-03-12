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
import pl.marcinmilkowski.word_sketch.utils.RelationPatternUtils;
import pl.marcinmilkowski.word_sketch.model.exploration.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.exploration.DiscoveredNoun;
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

    MultiSeedExplorer(QueryExecutor executor) {
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
            double minLogDice,
            int topCollocates,
            int minShared) throws IOException {

        SeedCollocateData data = fetchCollocatesPerSeed(seeds, relationConfig, minLogDice, topCollocates);
        Set<String> commonCollocates = identifyCommonCollocates(data.collocateSharedCount(), minShared, seeds.size());

        AggregatedCollocateStats stats = aggregateCollocateStats(data.seedCollocateMap());
        List<DiscoveredNoun> discoveredNounsList = buildDiscoveredNouns(seeds, data.seedCollocateMap(), commonCollocates);
        List<CoreCollocate> coreCollocatesList = buildCoreCollocates(
                commonCollocates, data.collocateSharedCount(), stats.maxLogDiceByLemma(), stats.avgLogDiceByLemma(), seeds.size());

        Map<String, Map<String, Double>> perSeedCollocates = new LinkedHashMap<>();
        for (Map.Entry<String, List<QueryResults.WordSketchResult>> entry : data.seedCollocateMap().entrySet()) {
            Map<String, Double> collocMap = new LinkedHashMap<>();
            for (QueryResults.WordSketchResult wsr : entry.getValue()) {
                collocMap.put(wsr.lemma(), wsr.logDice());
            }
            perSeedCollocates.put(entry.getKey(), collocMap);
        }

        return new ExplorationResult(
            new java.util.ArrayList<>(seeds),
            stats.maxLogDiceByLemma(), stats.totalFreqByLemma(),
            discoveredNounsList, coreCollocatesList, perSeedCollocates);
    }

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
            for (QueryResults.WordSketchResult wsr : collocates) {
                collocateSharedCount.merge(wsr.lemma(), 1, Integer::sum);
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
            for (QueryResults.WordSketchResult wsr : collocs) {
                if (commonCollocates.contains(wsr.lemma())) {
                    sharedCollocs.put(wsr.lemma(), wsr.logDice());
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
            Map<String, Double> seedCollocScores,
            Map<String, Double> avgLogDiceMap,
            int numSeeds) {
        List<CoreCollocate> coreCollocatesList = new ArrayList<>();
        for (String c : commonCollocates) {
            int sharedBy = collocateSharedCount.getOrDefault(c, 0);
            double avgLogDice = avgLogDiceMap.getOrDefault(c, 0.0);
            double seedLd = seedCollocScores.getOrDefault(c, 0.0);
            coreCollocatesList.add(new CoreCollocate(c, sharedBy, numSeeds, seedLd, avgLogDice));
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
            for (QueryResults.WordSketchResult wsr : collocs) {
                maxLogDice.merge(wsr.lemma(), wsr.logDice(), Math::max);
                totalFreq.merge(wsr.lemma(), wsr.frequency(), Long::sum);
                logDiceSum.merge(wsr.lemma(), wsr.logDice(), Double::sum);
                logDiceCount.merge(wsr.lemma(), 1, Integer::sum);
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

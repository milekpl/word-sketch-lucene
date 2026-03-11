package pl.marcinmilkowski.word_sketch.exploration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.model.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.model.ExplorationResult;
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
     * Fetches collocates for each seed using the given relation and maps the results into an
     * {@link ExplorationResult}. Seeds become the {@code discoveredNouns} (each carrying their
     * common collocates as shared-collocate set); the collocate intersection becomes
     * {@code coreCollocates}; and the aggregate collocate map becomes {@code seedCollocates}.
     */
    ExplorationResult explore(
            Set<String> seeds,
            RelationConfig relationConfig,
            double minLogDice,
            int topCollocates,
            int minShared) throws IOException {

        // Phase 1: fetch collocates per seed
        SeedCollocateData data = fetchCollocatesPerSeed(seeds, relationConfig, minLogDice, topCollocates);

        // Phase 2: identify common collocates meeting minShared threshold
        Set<String> commonCollocates = identifyCommonCollocates(data.collocateSharedCount(), minShared, seeds.size());

        // Phase 3: build aggregate score / frequency maps, then DiscoveredNoun list
        Map<String, Double> seedCollocScores = new LinkedHashMap<>();
        Map<String, Long> seedCollocFreqs = new LinkedHashMap<>();
        for (List<QueryResults.WordSketchResult> collocs : data.seedCollocateMap().values()) {
            for (QueryResults.WordSketchResult wsr : collocs) {
                seedCollocScores.merge(wsr.lemma(), wsr.logDice(), Math::max);
                seedCollocFreqs.merge(wsr.lemma(), wsr.frequency(), Long::sum);
            }
        }
        List<DiscoveredNoun> discoveredNounsList = buildDiscoveredNouns(seeds, data.seedCollocateMap(), commonCollocates);

        // Phase 4: build core collocates
        List<CoreCollocate> coreCollocatesList = buildCoreCollocates(
                commonCollocates, data.collocateSharedCount(), seedCollocScores, data.seedCollocateMap(), seeds.size());

        return new ExplorationResult(
            String.join(",", seeds),
            seedCollocScores, seedCollocFreqs,
            discoveredNounsList, coreCollocatesList);
    }

    // ==================== PHASE HELPERS ====================

    /** Phase 1: execute the collocate query for each seed; returns per-seed results and shared-count map. */
    private SeedCollocateData fetchCollocatesPerSeed(
            Set<String> seeds, RelationConfig relationConfig,
            double minLogDice, int topCollocates) throws IOException {
        Map<String, List<QueryResults.WordSketchResult>> seedCollocateMap = new LinkedHashMap<>();
        Map<String, Integer> collocateSharedCount = new HashMap<>();
        for (String seed : seeds) {
            String bcqlPattern = relationConfig.buildFullPattern(seed);
            List<QueryResults.WordSketchResult> collocates = executor.executeSurfacePattern(
                seed, bcqlPattern, minLogDice, topCollocates);
            seedCollocateMap.put(seed, collocates);
            for (QueryResults.WordSketchResult wsr : collocates) {
                collocateSharedCount.merge(wsr.lemma(), 1, Integer::sum);
            }
        }
        return new SeedCollocateData(seedCollocateMap, collocateSharedCount);
    }

    /** Phase 2: return collocates that appear in at least {@code minShared} seeds (capped to seeds.size()). */
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

    /** Phase 3: build a {@link DiscoveredNoun} for each seed, capturing its shared-collocate subset. */
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
            double avg = sharedCollocs.isEmpty() ? 0.0
                : sharedCollocs.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double sum = sharedCollocs.values().stream().mapToDouble(Double::doubleValue).sum();
            discoveredNounsList.add(new DiscoveredNoun(seed, sharedCollocs, count, sum, avg));
        }
        return discoveredNounsList;
    }

    /** Phase 4: build a {@link CoreCollocate} for each member of the common collocate set. */
    private List<CoreCollocate> buildCoreCollocates(
            Set<String> commonCollocates,
            Map<String, Integer> collocateSharedCount,
            Map<String, Double> seedCollocScores,
            Map<String, List<QueryResults.WordSketchResult>> seedCollocateMap,
            int numSeeds) {
        List<CoreCollocate> coreCollocatesList = new ArrayList<>();
        for (String c : commonCollocates) {
            int sharedBy = collocateSharedCount.getOrDefault(c, 0);
            double avgLd = seedCollocateMap.values().stream()
                .flatMap(List::stream)
                .filter(wsr -> c.equals(wsr.lemma()))
                .mapToDouble(QueryResults.WordSketchResult::logDice)
                .average().orElse(0.0);
            double seedLd = seedCollocScores.getOrDefault(c, 0.0);
            coreCollocatesList.add(new CoreCollocate(c, sharedBy, numSeeds, seedLd, avgLd));
        }
        return coreCollocatesList;
    }

    private record SeedCollocateData(
            Map<String, List<QueryResults.WordSketchResult>> seedCollocateMap,
            Map<String, Integer> collocateSharedCount) {}
}

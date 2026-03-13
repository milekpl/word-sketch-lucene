package pl.marcinmilkowski.word_sketch.exploration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pl.marcinmilkowski.word_sketch.model.exploration.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.exploration.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.exploration.SingleSeedExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.sketch.*;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

/**
 * Algorithm class for single-seed semantic field exploration.
 *
 * <p>Owns the four-phase single-seed algorithm:</p>
 * <ol>
 *   <li>Fetch collocates of the seed word (via BCQL pattern, with simple-pattern fallback)</li>
 *   <li>For each top collocate, find other nouns it collocates with (reverse lookup)</li>
 *   <li>Score candidate nouns by shared-collocate count; discard those below {@code minShared}</li>
 *   <li>Identify core collocates shared by most discovered nouns</li>
 * </ol>
 *
 * <p>This class is package-private and is wired by {@link SemanticFieldExplorer}, which acts as
 * the coordination facade for the HTTP exploration layer, mirroring the package-private design
 * of {@link MultiSeedExplorer} and {@link CollocateProfileComparator}.</p>
 */
class SingleSeedExplorer {

    private static final Logger logger = LoggerFactory.getLogger(SingleSeedExplorer.class);

    private final QueryExecutor executor;
    private final String nounCqlPattern;

    SingleSeedExplorer(QueryExecutor executor, String nounCqlPattern) {
        this.executor = executor;
        this.nounCqlPattern = nounCqlPattern;
    }

    /**
     * Explore semantic field using pre-resolved BCQL pattern strings.
     *
     * <p><strong>Package-private algorithm entry point.</strong>
     * {@link SemanticFieldExplorer#exploreByRelation(String, pl.marcinmilkowski.word_sketch.config.RelationConfig, SingleSeedExplorationOptions)}
     * is the preferred public API — it resolves pattern strings from a
     * {@link pl.marcinmilkowski.word_sketch.config.RelationConfig} before delegating here.
     * This overload accepts pre-resolved BCQL strings directly, which also allows unit
     * tests to supply programmatically constructed patterns without a full grammar config.</p>
     *
     * @param seed          the seed noun to explore from
     * @param relationName  human-readable relation name for logging
     * @param bcqlPattern   BCQL pattern with headword already substituted
     * @param simplePattern simple reverse-lookup pattern (e.g., {@code [xpos="JJ.*"]})
     * @param opts          all tuning parameters including {@code reverseExpansionLimit}
     * @return ExplorationResult with discovered semantic class
     */
    ExplorationResult explore(
            String seed,
            String relationName,
            String bcqlPattern,
            String simplePattern,
            SingleSeedExplorationOptions opts) throws IOException {

        if (seed == null || seed.isEmpty()) {
            throw new IllegalArgumentException("seed must not be blank");
        }

        int topPredicates = opts.topCollocates();
        int nounsPerPredicate = opts.reverseExpansionLimit();
        double minLogDice = opts.logDiceThreshold();
        int minShared = opts.minShared();

        String normalizedSeed = seed.toLowerCase().trim();

        List<WordSketchResult> seedRelations = fetchSeedCollocates(
            normalizedSeed, bcqlPattern, simplePattern, minLogDice, topPredicates);

        if (seedRelations.isEmpty()) {
            logger.debug("No collocates found for seed '{}' — seed may be too rare", normalizedSeed);
            return ExplorationResult.empty(normalizedSeed);
        }

        Map<String, Double> seedCollocateScores = new LinkedHashMap<>();
        Map<String, Long> seedCollocateFrequencies = new LinkedHashMap<>();
        for (WordSketchResult r : seedRelations) {
            String lowerLemma = r.lemma().toLowerCase();
            seedCollocateScores.put(lowerLemma, r.logDice());
            seedCollocateFrequencies.put(lowerLemma, r.frequency());
        }

        Map<String, Map<String, Double>> nounProfiles = buildNounToCollocatesMap(
            seedCollocateScores, normalizedSeed, minLogDice, nounsPerPredicate);

        List<DiscoveredNoun> discoveredNouns = filterByMinShared(nounProfiles, minShared);
        discoveredNouns.sort((a, b) -> Double.compare(b.combinedRelevanceScore(), a.combinedRelevanceScore()));

        List<CoreCollocate> coreCollocates = identifyCoreCollocates(seedCollocateScores, discoveredNouns);

        return ExplorationResult.of(List.of(normalizedSeed), seedCollocateScores, seedCollocateFrequencies,
                discoveredNouns, coreCollocates, Map.of(normalizedSeed, seedCollocateScores));
    }

    /**
     * Phase 1: Fetch seed collocates using the BCQL pattern, with fallback to simplePattern.
     * Tries {@code executeSurfacePattern} first for grammatical precision; if that returns
     * nothing, falls back to {@code executeCollocations} via the dependency index.
     */
    private List<WordSketchResult> fetchSeedCollocates(
            String seed, String bcqlPattern, String simplePattern,
            double minLogDice, int topPredicates) throws IOException {
        List<WordSketchResult> results = executor.executeSurfacePattern(
            bcqlPattern, minLogDice, topPredicates);
        if (results.isEmpty()) {
            logger.debug("  No results found for seed word. Trying fallback to simple pattern...");
            results = executor.executeCollocations(seed, simplePattern, minLogDice, topPredicates);
        }
        return results;
    }

    /**
     * Phase 2: For each collocate, find nouns it collocates with (reverse lookup).
     * Returns a map of noun → {collocate → logDice}.
     *
     * <p><b>I/O fan-out:</b> issues one {@code executeCollocations} call per collocate in
     * {@code seedCollocateScores}, so up to {@code topPredicates} sequential network/disk
     * round-trips may occur. Keep {@code topPredicates} small (≤ 20) for interactive use.</p>
     */
    private Map<String, Map<String, Double>> buildNounToCollocatesMap(
            Map<String, Double> seedCollocateScores, String seed,
            double minLogDice, int nounsPerPredicate) throws IOException {
        Map<String, Map<String, Double>> nounProfiles = new LinkedHashMap<>();
        for (String collocate : seedCollocateScores.keySet()) {
            List<WordSketchResult> nouns = executor.executeCollocations(
                collocate, nounCqlPattern, minLogDice, nounsPerPredicate);
            for (WordSketchResult r : nouns) {
                String noun = r.lemma().toLowerCase();
                if (noun.equals(seed)) continue;
                nounProfiles.computeIfAbsent(noun, k -> new LinkedHashMap<>())
                    .put(collocate, r.logDice());
            }
        }
        return nounProfiles;
    }

    /**
     * Phase 3: Score candidate nouns by how many shared collocates they have.
     * Nouns below {@code minShared} are filtered out.
     */
    private List<DiscoveredNoun> filterByMinShared(
            Map<String, Map<String, Double>> nounProfiles, int minShared) {
        List<DiscoveredNoun> discoveredNouns = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> entry : nounProfiles.entrySet()) {
            String noun = entry.getKey();
            Map<String, Double> collocateScores = entry.getValue();
            int sharedCount = collocateScores.size();
            if (sharedCount < minShared) continue;
            double combinedRelevanceScore = collocateScores.values().stream()
                    .mapToDouble(Double::doubleValue).sum();
            double avgLogDice = combinedRelevanceScore / sharedCount;
            discoveredNouns.add(new DiscoveredNoun(
                noun, collocateScores, sharedCount, combinedRelevanceScore, avgLogDice));
        }
        return discoveredNouns;
    }

    /**
     * Minimum fraction of discovered nouns a collocate must co-occur with
     * to qualify as a "core" collocate. A 1/3 threshold ensures a collocate
     * is broadly shared rather than specific to just one or two nouns.
     */
    private static final double CORE_COLLOCATE_MIN_NOUN_FRACTION = 1.0 / 3.0;

    private List<CoreCollocate> identifyCoreCollocates(
            Map<String, Double> seedCollocateScores, List<DiscoveredNoun> discoveredNouns) {
        Map<String, Integer> collocateFrequency = new LinkedHashMap<>();
        Map<String, Double> collocateTotalScore = new LinkedHashMap<>();
        for (DiscoveredNoun dn : discoveredNouns) {
            for (Map.Entry<String, Double> collocate : dn.sharedCollocates().entrySet()) {
                collocateFrequency.merge(collocate.getKey(), 1, Integer::sum);
                collocateTotalScore.merge(collocate.getKey(), collocate.getValue(), Double::sum);
            }
        }
        int minNounsForCore = Math.max(2,
                (int) Math.ceil(discoveredNouns.size() * CORE_COLLOCATE_MIN_NOUN_FRACTION));
        List<CoreCollocate> coreCollocates = new ArrayList<>();
        for (String collocate : seedCollocateScores.keySet()) {
            int freq = collocateFrequency.getOrDefault(collocate, 0);
            if (freq >= minNounsForCore) {
                double totalScore = collocateTotalScore.getOrDefault(collocate, 0.0);
                double seedScore = seedCollocateScores.get(collocate);
                coreCollocates.add(new CoreCollocate(
                    collocate, freq, discoveredNouns.size(), seedScore, totalScore / freq));
            }
        }
        coreCollocates.sort((a, b) -> {
            int cmp = Integer.compare(b.sharedByCount(), a.sharedByCount());
            return cmp != 0 ? cmp : Double.compare(b.avgLogDice(), a.avgLogDice());
        });
        return coreCollocates;
    }
}

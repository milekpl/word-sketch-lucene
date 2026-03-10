package pl.marcinmilkowski.word_sketch.exploration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.model.AdjectiveProfile;
import pl.marcinmilkowski.word_sketch.model.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.model.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

/**
 * Compares adjective collocate profiles across a set of seed nouns to reveal shared
 * and distinctive collocates.  Extracted from {@link SemanticFieldExplorer} to keep
 * that class below the 400-line threshold.
 */
public class CollocateProfileComparator {

    private static final Logger logger = LoggerFactory.getLogger(CollocateProfileComparator.class);

    private static final String ADJECTIVE_PATTERN = "[xpos=\"JJ.*\"]";

    private final QueryExecutor executor;

    public CollocateProfileComparator(QueryExecutor executor) {
        this.executor = executor;
    }

    /**
     * Compare adjective collocate profiles across a set of seed nouns, revealing which
     * adjectives are shared across seeds (commonality) and which are distinctive to individual seeds.
     *
     * @param seedNouns   Nouns to compare (e.g., "theory", "model", "hypothesis")
     * @param minLogDice  Minimum logDice score for adjective-noun pairs
     * @param maxPerNoun  Maximum adjectives to retrieve per noun
     * @return ComparisonResult with graded adjective profiles
     */
    public ComparisonResult compareCollocateProfiles(
            Set<String> seedNouns,
            double minLogDice,
            int maxPerNoun) throws IOException {

        if (seedNouns == null || seedNouns.isEmpty()) {
            return ComparisonResult.empty();
        }

        List<String> nounList = new ArrayList<>(seedNouns);

        logger.debug("\n=== SEMANTIC FIELD COMPARISON ===");
        logger.debug("Nouns: {}", seedNouns);
        logger.debug("Min logDice: {}", minLogDice);
        logger.debug("------------------------------------------------------------");

        Map<String, Map<String, Double>> rawProfiles = buildRawAdjectiveProfiles(nounList, minLogDice, maxPerNoun);
        List<AdjectiveProfile> profiles = buildAdjectiveProfileList(nounList, rawProfiles);

        // Sort by commonality score (most shared first)
        profiles.sort((a, b) -> Double.compare(b.commonalityScore, a.commonalityScore));

        logger.debug("Total unique adjectives: {}", profiles.size());
        logTopProfiles(profiles);
        logger.debug("------------------------------------------------------------");

        return new ComparisonResult(nounList, profiles);
    }

    /**
     * Phase 1: Collect adjective collocates for each noun and index them by adjective.
     *
     * @return Map of adjective → (noun → logDice score)
     */
    private Map<String, Map<String, Double>> buildRawAdjectiveProfiles(
            List<String> nounList, double minLogDice, int maxPerNoun) throws IOException {

        Map<String, Map<String, Double>> adjectiveProfiles = new LinkedHashMap<>();
        for (String noun : nounList) {
            logger.debug("\nProfiling: {}", noun);
            List<QueryResults.WordSketchResult> adjectives = executor.findCollocations(
                noun, ADJECTIVE_PATTERN, minLogDice, maxPerNoun);
            logger.debug("  Found {} adjectives", adjectives.size());
            if (!adjectives.isEmpty()) {
                logger.debug("  Top 5: {}", adjectives.subList(0, Math.min(5, adjectives.size())));
            }
            for (QueryResults.WordSketchResult r : adjectives) {
                adjectiveProfiles
                    .computeIfAbsent(r.getLemma().toLowerCase(), k -> new LinkedHashMap<>())
                    .put(noun, r.getLogDice());
            }
        }
        return adjectiveProfiles;
    }

    /**
     * Phase 2: Build AdjectiveProfile objects from raw per-noun scores.
     */
    private List<AdjectiveProfile> buildAdjectiveProfileList(
            List<String> nounList,
            Map<String, Map<String, Double>> adjectiveProfiles) {

        int nounCount = nounList.size();
        logger.debug("\n--- Building Comparison Profiles ---");
        List<AdjectiveProfile> profiles = new ArrayList<>();

        for (Map.Entry<String, Map<String, Double>> entry : adjectiveProfiles.entrySet()) {
            String adj = entry.getKey();
            Map<String, Double> nounScores = entry.getValue();

            Map<String, Double> fullScores = new LinkedHashMap<>();
            for (String noun : nounList) {
                fullScores.put(noun, nounScores.getOrDefault(noun, 0.0));
            }

            int presentIn = nounScores.size();
            double[] scores = nounScores.values().stream().mapToDouble(Double::doubleValue).toArray();
            double avgScore = Arrays.stream(scores).average().orElse(0.0);
            double maxScore = Arrays.stream(scores).max().orElse(0.0);
            double minScore = Arrays.stream(scores).min().orElse(0.0);

            double variance = 0.0;
            if (scores.length > 1) {
                for (double s : scores) {
                    variance += (s - avgScore) * (s - avgScore);
                }
                variance /= scores.length;
            }

            double commonalityScore = presentIn * avgScore;
            double distinctivenessScore = maxScore * (1.0 - (double) presentIn / nounCount)
                                         + Math.sqrt(variance);

            profiles.add(new AdjectiveProfile(
                adj, fullScores, presentIn, nounCount,
                avgScore, maxScore, minScore, variance,
                commonalityScore, distinctivenessScore
            ));
        }
        return profiles;
    }

    private void logTopProfiles(List<AdjectiveProfile> profiles) {
        logger.debug("\nTop SHARED (high commonality):");
        profiles.stream()
            .filter(p -> p.presentInCount >= 2)
            .limit(10)
            .forEach(p -> logger.debug("  {} (in {}/{} nouns, avg={})", p.adjective,
                p.presentInCount, p.totalNouns, String.format("%.2f", p.avgLogDice)));

        logger.debug("\nTop DISTINCTIVE (specific to 1 noun):");
        profiles.stream()
            .filter(p -> p.presentInCount == 1)
            .sorted((a, b) -> Double.compare(b.maxLogDice, a.maxLogDice))
            .limit(10)
            .forEach(p -> {
                String specificNoun = p.nounScores.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .findFirst().orElse("?");
                logger.debug("  {} -> {} ({})", p.adjective, specificNoun, String.format("%.2f", p.maxLogDice));
            });
    }

    /**
     * Fetches collocates for each seed using the given relation and maps the results into an
     * {@link ExplorationResult}.  Seeds become the {@code discoveredNouns} (each carrying their
     * common collocates as shared-collocate set); the collocate intersection becomes
     * {@code coreCollocates}; and the aggregate collocate map becomes {@code seedCollocates}.
     *
     * @param seeds          ordered seed words (at least 2)
     * @param relationConfig grammar relation to use for collocate lookup
     * @param minLogDice     minimum logDice threshold for inclusion
     * @param topCollocates  maximum collocates to fetch per seed
     * @param minShared      minimum number of seeds a collocate must appear in to be
     *                       included in the core set; use {@code seeds.size()}
     *                       to require presence in all seeds
     * @return ExplorationResult mapping multi-seed data into the shared exploration model
     */
    public ExplorationResult exploreMultiSeed(
            Set<String> seeds,
            RelationConfig relationConfig,
            double minLogDice,
            int topCollocates,
            int minShared) throws IOException {
        Map<String, List<QueryResults.WordSketchResult>> seedCollocateMap = new LinkedHashMap<>();
        Map<String, Integer> collocateSharedCount = new HashMap<>();

        for (String seed : seeds) {
            String bcqlPattern = relationConfig.getFullPattern(seed);
            List<QueryResults.WordSketchResult> collocates = executor.executeSurfacePattern(
                seed, bcqlPattern,
                minLogDice, topCollocates);
            seedCollocateMap.put(seed, collocates);

            for (QueryResults.WordSketchResult wsr : collocates) {
                collocateSharedCount.merge(wsr.getLemma(), 1, Integer::sum);
            }
        }

        int threshold = Math.min(minShared, seeds.size());
        Set<String> commonCollocates = new HashSet<>();
        for (Map.Entry<String, Integer> entry : collocateSharedCount.entrySet()) {
            if (entry.getValue() >= threshold) {
                commonCollocates.add(entry.getKey());
            }
        }

        // Aggregate collocate → max logDice / total frequency across all seeds
        Map<String, Double> seedCollocScores = new LinkedHashMap<>();
        Map<String, Long> seedCollocFreqs = new LinkedHashMap<>();
        for (List<QueryResults.WordSketchResult> collocs : seedCollocateMap.values()) {
            for (QueryResults.WordSketchResult wsr : collocs) {
                seedCollocScores.merge(wsr.getLemma(), wsr.getLogDice(), Math::max);
                seedCollocFreqs.merge(wsr.getLemma(), wsr.getFrequency(), Long::sum);
            }
        }

        // Each input seed becomes a DiscoveredNoun whose sharedCollocates are the common collocates
        int numSeeds = seeds.size();
        List<DiscoveredNoun> discoveredNounsList = new ArrayList<>();
        for (String seed : seeds) {
            List<QueryResults.WordSketchResult> collocs = seedCollocateMap.getOrDefault(seed, List.of());
            Map<String, Double> sharedCollocs = new LinkedHashMap<>();
            for (QueryResults.WordSketchResult wsr : collocs) {
                if (commonCollocates.contains(wsr.getLemma())) {
                    sharedCollocs.put(wsr.getLemma(), wsr.getLogDice());
                }
            }
            int count = sharedCollocs.size();
            double avg = sharedCollocs.isEmpty() ? 0.0
                : sharedCollocs.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double sum = sharedCollocs.values().stream().mapToDouble(Double::doubleValue).sum();
            discoveredNounsList.add(new DiscoveredNoun(seed, sharedCollocs, count, sum, avg, count * avg));
        }

        // Common collocates become the coreCollocates
        List<CoreCollocate> coreCollocatesList = new ArrayList<>();
        for (String c : commonCollocates) {
            int sharedBy = collocateSharedCount.getOrDefault(c, 0);
            double avgLd = seedCollocateMap.values().stream()
                .flatMap(List::stream)
                .filter(wsr -> c.equals(wsr.getLemma()))
                .mapToDouble(QueryResults.WordSketchResult::getLogDice)
                .average().orElse(0.0);
            double seedLd = seedCollocScores.getOrDefault(c, 0.0);
            coreCollocatesList.add(new CoreCollocate(c, sharedBy, numSeeds, seedLd, avgLd));
        }

        return new ExplorationResult(
            String.join(",", seeds),
            seedCollocScores, seedCollocFreqs,
            discoveredNounsList, coreCollocatesList);
    }
}

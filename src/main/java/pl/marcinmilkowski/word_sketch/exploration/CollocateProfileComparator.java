package pl.marcinmilkowski.word_sketch.exploration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.config.RelationUtils;
import pl.marcinmilkowski.word_sketch.model.AdjectiveProfile;
import pl.marcinmilkowski.word_sketch.model.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.PosGroup;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.model.RelationType;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

/**
 * Compares collocate profiles across multiple seed nouns to reveal shared and distinctive
 * collocates. Given a set of seed nouns, fetches adjective collocates for each and computes
 * commonality and distinctiveness scores across the full set.
 */
class CollocateProfileComparator {

    private static final Logger logger = LoggerFactory.getLogger(CollocateProfileComparator.class);

    private static final String FALLBACK_ADJECTIVE_PATTERN = "[xpos=\"JJ.*\"]";

    private final QueryExecutor executor;
    private final String adjectivePattern;

    CollocateProfileComparator(QueryExecutor executor, GrammarConfig grammarConfig) {
        this.executor = executor;
        this.adjectivePattern = deriveAdjectivePattern(grammarConfig);
    }

    private static String deriveAdjectivePattern(GrammarConfig grammarConfig) {
        return RelationUtils.findBestCollocatePattern(
            grammarConfig, PosGroup.ADJ, FALLBACK_ADJECTIVE_PATTERN,
            RelationType.ADJ_MODIFIER, RelationType.ADJ_PREDICATE);
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
            @NonNull Set<String> seedNouns,
            double minLogDice,
            int maxPerNoun) throws IOException {

        if (seedNouns.size() < 2) {
            throw new IllegalArgumentException(
                "compareCollocateProfiles requires at least 2 seed nouns; got: " + seedNouns.size());
        }

        List<String> nounList = new ArrayList<>(seedNouns);

        logger.debug("Nouns: {}", seedNouns);
        logger.debug("Min logDice: {}", minLogDice);

        Map<String, Map<String, Double>> rawProfiles = buildRawAdjectiveProfiles(nounList, minLogDice, maxPerNoun);
        List<AdjectiveProfile> profiles = buildAdjectiveProfileList(nounList, rawProfiles);

        // Sort by commonality score (most shared first)
        profiles.sort((a, b) -> Double.compare(b.commonalityScore(), a.commonalityScore()));

        logger.debug("Total unique adjectives: {}", profiles.size());
        logTopProfiles(profiles);

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
            List<QueryResults.WordSketchResult> adjectives = executor.executeCollocations(
                noun, adjectivePattern, minLogDice, maxPerNoun);
            logger.debug("Profiling {}: {} adjectives (top: {})", noun, adjectives.size(),
                adjectives.subList(0, Math.min(5, adjectives.size())));
            for (QueryResults.WordSketchResult r : adjectives) {
                adjectiveProfiles
                    .computeIfAbsent(r.lemma().toLowerCase(), k -> new LinkedHashMap<>())
                    .put(noun, r.logDice());
            }
        }
        return adjectiveProfiles;
    }

    /**
     * Phase 2: Build AdjectiveProfile objects from raw per-noun scores.
     * Uses a single pass over scores to compute average, max, min, and variance together.
     */
    private List<AdjectiveProfile> buildAdjectiveProfileList(
            List<String> nounList,
            Map<String, Map<String, Double>> adjectiveProfiles) {

        int nounCount = nounList.size();
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

            // Single-pass: accumulate sum, min, max in one loop
            double sum = 0.0, maxScore = Double.NEGATIVE_INFINITY, minScore = Double.POSITIVE_INFINITY;
            for (double s : scores) {
                sum += s;
                if (s > maxScore) maxScore = s;
                if (s < minScore) minScore = s;
            }
            double avgScore = scores.length > 0 ? sum / scores.length : 0.0;

            // Second pass for variance (requires the mean — unavoidable two-pass for numerical stability)
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
        if (!logger.isDebugEnabled()) return;
        profiles.stream()
            .filter(p -> p.presentInCount() >= 2)
            .limit(10)
            .forEach(p -> logger.debug("  {} (in {}/{} nouns, avg={})", p.adjective(),
                p.presentInCount(), p.totalNouns(), String.format("%.2f", p.avgLogDice())));

        profiles.stream()
            .filter(p -> p.presentInCount() == 1)
            .sorted((a, b) -> Double.compare(b.maxLogDice(), a.maxLogDice()))
            .limit(10)
            .forEach(p -> {
                String specificNoun = p.nounScores().entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .findFirst().orElse("?");
                logger.debug("  {} -> {} ({})", p.adjective(), specificNoun, String.format("%.2f", p.maxLogDice()));
            });
    }
}

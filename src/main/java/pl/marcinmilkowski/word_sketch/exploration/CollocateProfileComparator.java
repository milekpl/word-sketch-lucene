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
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.config.RelationUtils;
import pl.marcinmilkowski.word_sketch.model.exploration.CollocateProfile;
import pl.marcinmilkowski.word_sketch.model.exploration.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.sketch.*;
import pl.marcinmilkowski.word_sketch.query.spi.SketchQueryPort;

/**
 * Compares collocate profiles across multiple seed nouns to reveal shared and distinctive
 * collocates. Given a set of seed nouns, fetches relation-scoped collocates for each and computes
 * commonality and distinctiveness scores across the full set.
 */
class CollocateProfileComparator {

    private static final Logger logger = LoggerFactory.getLogger(CollocateProfileComparator.class);

    private final SketchQueryPort executor;

    CollocateProfileComparator(SketchQueryPort executor) {
        this.executor = executor;
    }

    /**
    * Compare relation-scoped collocate profiles across a set of seed nouns, revealing which
    * collocates are shared across seeds (commonality) and which are distinctive to individual seeds.
     *
     * @param seedNouns   Nouns to compare (e.g., "theory", "model", "hypothesis")
     * @param opts        exploration options; {@code topCollocates} and {@code minLogDice} are used
    * @return ComparisonResult with graded collocate profiles
     */
    public ComparisonResult compareCollocateProfiles(
            @NonNull Set<String> seedNouns,
            @NonNull RelationConfig relationConfig,
            @NonNull ExplorationOptions opts) throws IOException {

        relationConfig.validate();

        double minLogDice = opts.logDiceThreshold();
        int maxPerNoun = opts.topCollocates();
        List<String> nounList = new ArrayList<>(seedNouns);

        Map<String, Map<String, Double>> rawProfiles = buildRawCollocateProfiles(nounList, relationConfig, minLogDice, maxPerNoun);
        List<CollocateProfile> profiles = buildCollocateProfileList(nounList, rawProfiles);

        // Sort by commonality score (most shared first)
        profiles.sort((a, b) -> Double.compare(b.commonalityScore(), a.commonalityScore()));

        debugLogTopProfiles(profiles);

        return ComparisonResult.of(nounList, profiles);
    }

    /**
     * Phase 1: Collect collocates for each noun and index them by collocate.
     * Uses the relation-specific surface pattern first and falls back to reverse lookup only
     * when the surface query produces no matches for a seed.
     *
     * @return Map of collocate → (noun → logDice score)
     */
    private Map<String, Map<String, Double>> buildRawCollocateProfiles(
            List<String> nounList, RelationConfig relationConfig, double minLogDice, int maxPerNoun) throws IOException {

        Map<String, Map<String, Double>> collocateProfiles = new LinkedHashMap<>();
        for (String noun : nounList) {
            List<WordSketchResult> collocates = fetchSeedCollocates(noun, relationConfig, minLogDice, maxPerNoun);
            logger.debug("Profiling {}: {} collocates", noun, collocates.size());
            for (WordSketchResult r : collocates) {
                collocateProfiles
                    .computeIfAbsent(r.lemma().toLowerCase(), k -> new LinkedHashMap<>())
                    .put(noun, r.logDice());
            }
        }
        return collocateProfiles;
    }

    private List<WordSketchResult> fetchSeedCollocates(
            String seed, RelationConfig relationConfig, double minLogDice, int maxPerNoun) throws IOException {
        String surfacePattern = RelationUtils.buildFullPattern(relationConfig, seed);
        List<WordSketchResult> results = executor.executeSurfaceCollocations(surfacePattern, minLogDice, maxPerNoun);
        if (results.isEmpty()) {
            String reversePattern = RelationUtils.buildCollocateReversePattern(relationConfig);
            results = executor.executeCollocations(seed, reversePattern, minLogDice, maxPerNoun);
        }
        return results;
    }

    /**
     * Phase 2: Build CollocateProfile objects from raw per-noun scores.
     * Uses a single pass over scores to compute average, max, min, and variance together.
     */
    private List<CollocateProfile> buildCollocateProfileList(
            List<String> nounList,
            Map<String, Map<String, Double>> collocateProfiles) {

        int nounCount = nounList.size();
        List<CollocateProfile> profiles = new ArrayList<>();

        for (Map.Entry<String, Map<String, Double>> entry : collocateProfiles.entrySet()) {
            String collocate = entry.getKey();
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
            double avgScore = sum / scores.length;

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

            profiles.add(new CollocateProfile(
                collocate, fullScores, presentIn, nounCount,
                avgScore, maxScore, minScore, variance,
                commonalityScore, distinctivenessScore
            ));
        }
        return profiles;
    }

    private void debugLogTopProfiles(List<CollocateProfile> profiles) {
        if (!logger.isDebugEnabled()) return;
        profiles.stream()
            .filter(p -> p.presentInCount() >= 2)
            .limit(10)
            .forEach(p -> logger.debug("  {} (in {}/{} nouns, avg={})", p.collocate(),
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
                logger.debug("  {} -> {} ({})", p.collocate(), specificNoun, String.format("%.2f", p.maxLogDice()));
            });
    }
}

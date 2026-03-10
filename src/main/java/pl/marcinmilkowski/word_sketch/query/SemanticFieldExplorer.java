package pl.marcinmilkowski.word_sketch.query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.model.AdjectiveProfile;
import pl.marcinmilkowski.word_sketch.model.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.model.Edge;
import pl.marcinmilkowski.word_sketch.model.ExploreOptions;
import pl.marcinmilkowski.word_sketch.model.ExplorationResult;

/**
 * Analyses semantic fields around seed words by querying collocate patterns.
 * Supports both single-seed exploration and multi-seed comparison to find
 * common and distinctive collocates across seeds.
 *
 * <h2>Exploration Mode (single seed)</h2>
 * <p>Starting from a seed word, discovers semantically related words by:</p>
 * <ol>
 *   <li>Find adjectives that modify the seed noun</li>
 *   <li>For each top adjective, find OTHER nouns it modifies</li>
 *   <li>Score nouns by how many seed adjectives they share</li>
 *   <li>Build a semantic class of related nouns</li>
 * </ol>
 *
 * <h2>Example: Exploring from "house"</h2>
 * <pre>
 * Seed: house
 * Top adjectives: beautiful, old, big, small, private, new...
 *
 * "beautiful" modifies: beach, garden, view, apartment, landscape, house...
 * "old" modifies: town, city, friend, man, house, building...
 * "big" modifies: deal, city, house, hotel, family...
 *
 * Nouns sharing 3+ adjectives with "house":
 *   apartment (beautiful, old, big, small, new) - score: 5
 *   building (old, big, new) - score: 3
 *   hotel (beautiful, big, small, new) - score: 4
 *
 * => Semantic class: dwellings/places
 * => Core adjectives: beautiful, old, big, small, new (shared by class)
 * </pre>
 *
 * <h2>Comparison Mode (multi-seed)</h2>
 * <p>Given multiple seed words, compares their collocate profiles to reveal
 * which collocates are shared across all seeds (the semantic core) and which
 * are distinctive to individual seeds. See {@link #compareCollocateProfiles}.</p>
 *
 * <h2>Result classes</h2>
 * <p>Result DTOs ({@code ExplorationResult}, {@code DiscoveredNoun},
 * {@code CoreCollocate}, {@code ComparisonResult}, {@code AdjectiveProfile}, {@code Edge})
 * live in the {@code pl.marcinmilkowski.word_sketch.model} package.</p>
 *
 * <h2>Computational Limits</h2>
 * <p>Uses logDice thresholds at each step to prevent combinatorial explosion:</p>
 * <ul>
 *   <li>Top N adjectives from seed (default: 20)</li>
 *   <li>Top M nouns per adjective (default: 30)</li>
 *   <li>Minimum overlap threshold (default: 2 shared adjectives)</li>
 * </ul>
 */
public class SemanticFieldExplorer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SemanticFieldExplorer.class);

    private final QueryExecutor executor;
    private final String indexPath;
    private final boolean ownsExecutor;

    // Patterns for finding collocates by POS (using xpos field from CoNLL-U index)
    private static final String ADJECTIVE_PATTERN = "[xpos=\"JJ.*\"]";
    private static final String NOUN_PATTERN = "[xpos=\"NN.*\"]";


    /**
     * Convenience constructor that creates an owned {@link BlackLabQueryExecutor} internally.
     * Prefer {@link #SemanticFieldExplorer(QueryExecutor)} (dependency injection) for testability
     * and to avoid double-open of the same index (see issue 297b2b52).
     *
     * @deprecated Use {@link #SemanticFieldExplorer(QueryExecutor)} to receive an injected executor.
     */
    @Deprecated
    public SemanticFieldExplorer(String indexPath) throws IOException {
        this.indexPath = indexPath;
        this.executor = new BlackLabQueryExecutor(indexPath);
        this.ownsExecutor = true;
    }

    // Constructor for testing with mock executor
    public SemanticFieldExplorer(QueryExecutor executor) {
        this.indexPath = null;
        this.executor = executor;
        this.ownsExecutor = false;
    }

    // ==================== EXPLORATION MODE ====================

    /**
     * Explore semantic field using a BCQL pattern from grammar config.
     * This is the preferred method as it uses the actual patterns from relations.json.
     *
     * @param seed          The seed noun to explore from
     * @param relationName  Human-readable relation name for logging
     * @param bcqlPattern   BCQL pattern with headword placeholder
     * @param simplePattern Simple pattern for reverse lookup (e.g., "[xpos=\"JJ.*\"]")
     * @param opts          Tuning parameters and positional hints
     * @return ExplorationResult with discovered semantic class
     */
    public ExplorationResult exploreByPattern(
            String seed,
            String relationName,
            String bcqlPattern,
            String simplePattern,
            ExploreOptions opts) throws IOException {

        int topPredicates = opts.topCollocates();
        int nounsPerPredicate = opts.nounsPerCollocate();
        double minLogDice = opts.minLogDice();
        int minShared = opts.minShared();
        int headPos = opts.headPos();
        int collocatePos = opts.collocatePos();

        if (seed == null || seed.isEmpty()) {
            return ExplorationResult.empty(seed);
        }

        seed = seed.toLowerCase().trim();

        logger.debug("\n=== SEMANTIC FIELD EXPLORATION ===");
        logger.debug("Seed: {}", seed);
        logger.debug("Relation: {}", relationName);
        logger.debug("Pattern: {}", bcqlPattern);
        logger.debug("Head position: {}, Collocate position: {}", headPos, collocatePos);
        logger.debug("Parameters: top={}, nounsPerRel={}, minShared={}, minLogDice={}", topPredicates, nounsPerPredicate, minShared, minLogDice);
        logger.debug("------------------------------------------------------------");

        // Step 1: Get predicates/collocates for the seed noun using the BCQL pattern
        logger.debug("\nStep 1: Finding collocates for '{}'...", seed);

        // Use executeSurfacePattern which properly handles labeled BCQL patterns
        List<QueryResults.WordSketchResult> seedRelations;
        seedRelations = executor.executeSurfacePattern(
                seed, bcqlPattern, headPos, collocatePos, minLogDice, topPredicates);

        if (seedRelations.isEmpty()) {
            logger.debug("  No results found for seed word.");
            // Try fallback to simple pattern
            logger.debug("  Trying fallback to simple pattern...");
            seedRelations = executor.findCollocations(
                seed, simplePattern, minLogDice, topPredicates);
        }

        if (seedRelations.isEmpty()) {
            logger.debug("  Still no results. Seed may be too rare.");
            return ExplorationResult.empty(seed);
        }

        logger.debug("  Found {} collocates:", seedRelations.size());
        seedRelations.forEach(a -> logger.debug("    {} (logDice={})", a.getLemma(), String.format("%.2f", a.getLogDice())));

        // Build map: collocate -> logDice with seed
        Map<String, Double> seedCollocScores = new LinkedHashMap<>();
        for (QueryResults.WordSketchResult r : seedRelations) {
            seedCollocScores.put(r.getLemma().toLowerCase(), r.getLogDice());
        }

        // Step 2: For each collocate, find nouns it collocates with
        logger.debug("\nStep 2: Finding nouns for each collocate...");

        // noun -> {collocate -> logDice}
        Map<String, Map<String, Double>> nounProfiles = new LinkedHashMap<>();

        for (String colloc : seedCollocScores.keySet()) {
            // For adjectives, find nouns they modify (reverse relation)
            List<QueryResults.WordSketchResult> nouns = executor.findCollocations(
                colloc, NOUN_PATTERN, minLogDice, nounsPerPredicate);

            logger.debug("  {}: {} nouns", colloc, nouns.size());

            for (QueryResults.WordSketchResult r : nouns) {
                String noun = r.getLemma().toLowerCase();
                if (noun.equals(seed)) continue;

                nounProfiles
                    .computeIfAbsent(noun, k -> new LinkedHashMap<>())
                    .put(colloc, r.getLogDice());
            }
        }

        logger.debug("  Total candidate nouns: {}", nounProfiles.size());

        // Step 3: Score nouns by shared collocate count
        logger.debug("\nStep 3: Scoring nouns by shared {}...", relationName);

        List<DiscoveredNoun> discoveredNouns = new ArrayList<>();

        for (Map.Entry<String, Map<String, Double>> entry : nounProfiles.entrySet()) {
            String noun = entry.getKey();
            Map<String, Double> collocScores = entry.getValue();

            int sharedCount = collocScores.size();
            if (sharedCount < minShared) continue;

            double cumulativeScore = collocScores.values().stream()
                .mapToDouble(Double::doubleValue).sum();
            double avgLogDice = cumulativeScore / sharedCount;
            double similarityScore = sharedCount * avgLogDice;

            discoveredNouns.add(new DiscoveredNoun(
                noun, collocScores, sharedCount,
                cumulativeScore, avgLogDice, similarityScore
            ));
        }

        discoveredNouns.sort((a, b) -> Double.compare(b.similarityScore, a.similarityScore));

        logger.debug("  Nouns with {}+ shared: {}", minShared, discoveredNouns.size());

        // Step 4: Identify core collocates

        Map<String, Integer> collocFrequency = new LinkedHashMap<>();
        Map<String, Double> collocTotalScore = new LinkedHashMap<>();

        for (DiscoveredNoun dn : discoveredNouns) {
            for (Map.Entry<String, Double> colloc : dn.sharedCollocates.entrySet()) {
                collocFrequency.merge(colloc.getKey(), 1, Integer::sum);
                collocTotalScore.merge(colloc.getKey(), colloc.getValue(), Double::sum);
            }
        }

        List<CoreCollocate> coreCollocates = new ArrayList<>();
        int minNounsForCore = Math.max(2, discoveredNouns.size() / 3);

        for (String colloc : seedCollocScores.keySet()) {
            int freq = collocFrequency.getOrDefault(colloc, 0);
            double totalScore = collocTotalScore.getOrDefault(colloc, 0.0);
            double seedScore = seedCollocScores.get(colloc);

            if (freq >= minNounsForCore || freq >= 2) {
                coreCollocates.add(new CoreCollocate(
                    colloc, freq, discoveredNouns.size(),
                    seedScore, totalScore / Math.max(1, freq)
                ));
            }
        }

        coreCollocates.sort((a, b) -> {
            int cmp = Integer.compare(b.sharedByCount, a.sharedByCount);
            return cmp != 0 ? cmp : Double.compare(b.avgLogDice, a.avgLogDice);
        });

        // Print results
        logger.debug("\n--- RESULTS ---");
        logger.debug("\nSemantic class (nouns similar to '{}'):", seed);
        discoveredNouns.stream().limit(15).forEach(n ->
            logger.debug("  {} (shared={}, score={}) <- {}", n.noun, n.sharedCount,
                String.format("%.1f", n.similarityScore), String.join(", ", n.sharedCollocates.keySet())));

        logger.debug("\nCore {} (define the class):", relationName);
        coreCollocates.stream().limit(10).forEach(a ->
            logger.debug("  {} (in {}/{} nouns, avgLogDice={})", a.collocate, a.sharedByCount,
                a.totalNouns, String.format("%.1f", a.avgLogDice)));

        logger.debug("------------------------------------------------------------");

        return new ExplorationResult(seed, seedCollocScores, discoveredNouns, coreCollocates);
    }

    // ==================== COMPARISON MODE ====================

    /**
     * Compare adjective collocate profiles across a set of seed nouns, revealing which
     * adjectives are shared across seeds (commonality) and which are distinctive to individual seeds.
     *
     * @param seedNouns Nouns to compare (e.g., "theory", "model", "hypothesis")
     * @param minLogDice Minimum logDice score for adjective-noun pairs
     * @param maxPerNoun Maximum adjectives to retrieve per noun
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
     * Fetch example sentences for an adjective-noun pair.
     *
     * @param adjective The adjective lemma
     * @param noun The noun lemma
     * @param maxExamples Maximum number of examples to fetch
     * @return List of example sentences showing the adjective-noun combination
     */
    public List<String> fetchExamples(String adjective, String noun, int maxExamples) throws IOException {
        // Build BCQL query: adjective near noun
        // e.g., [lemma="good" & xpos="JJ.*"] []{0,3} [lemma="theory" & xpos="NN.*"]
        String bcqlQuery = String.format(
            "[lemma=\"%s\" & xpos=\"JJ.*\"] []{0,3} [lemma=\"%s\" & xpos=\"NN.*\"]",
            adjective.toLowerCase(), noun.toLowerCase()
        );

        List<QueryResults.ConcordanceResult> results = executor.executeBcqlQuery(bcqlQuery, maxExamples);

        return results.stream()
            .map(QueryResults.ConcordanceResult::getSentence)
            .distinct()
            .limit(maxExamples)
            .collect(Collectors.toList());
    }

    /**
     * Result of a multi-seed exploration. Carries per-seed collocates and the
     * intersection of collocate lemmas shared by all seeds.
     */
    public record MultiSeedCollocates(
        Map<String, List<QueryResults.WordSketchResult>> seedCollocates,
        Set<String> commonCollocates
    ) {}

    /**
     * Fetches collocates for each seed using the given relation and computes their
     * intersection.  The seeds are queried independently; the common-collocate set
     * contains lemmas whose surface form appears in at least {@code minShared} seeds'
     * collocate lists.
     *
     * @param seeds          ordered seed words (at least 2)
     * @param relationConfig grammar relation to use for collocate lookup
     * @param minLogDice     minimum logDice threshold for inclusion
     * @param topCollocates  maximum collocates to fetch per seed
     * @param minShared      minimum number of seeds a collocate must appear in to be
     *                       included in {@code commonCollocates}; use {@code seeds.size()}
     *                       to require presence in all seeds
     * @return per-seed collocate lists and their intersection
     */
    public MultiSeedCollocates exploreMultiSeed(
            Set<String> seeds,
            GrammarConfigLoader.RelationConfig relationConfig,
            double minLogDice,
            int topCollocates,
            int minShared) throws IOException {
        Map<String, List<QueryResults.WordSketchResult>> seedCollocates = new LinkedHashMap<>();
        Map<String, Integer> collocateSharedCount = new HashMap<>();

        for (String seed : seeds) {
            String bcqlPattern = relationConfig.getFullPattern(seed);
            List<QueryResults.WordSketchResult> collocates = executor.executeSurfacePattern(
                seed, bcqlPattern,
                relationConfig.headPosition(), relationConfig.collocatePosition(),
                minLogDice, topCollocates);
            seedCollocates.put(seed, collocates);

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

        return new MultiSeedCollocates(
            seedCollocates,
            commonCollocates != null ? commonCollocates : new HashSet<>());
    }

    @Override
    public void close() throws IOException {
        if (ownsExecutor && executor instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

}
package pl.marcinmilkowski.word_sketch.exploration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.query.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.model.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.DiscoveredNoun;
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
 * are distinctive to individual seeds. See {@link CollocateProfileComparator#compareCollocateProfiles}.</p>
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
public class SemanticFieldExplorer {

    private static final Logger logger = LoggerFactory.getLogger(SemanticFieldExplorer.class);

    private final QueryExecutor executor;
    private final CollocateProfileComparator comparator;

    // Patterns for finding collocates by POS (using xpos field from CoNLL-U index)
    private static final String NOUN_PATTERN = "[xpos=\"NN.*\"]";


    // Constructor for testing with mock executor
    public SemanticFieldExplorer(QueryExecutor executor) {
        this.executor = executor;
        this.comparator = new CollocateProfileComparator(executor);
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
    /**
     * Convenience overload that extracts pattern strings from a {@link RelationConfig},
     * mirroring the delegation style used by the multi-seed path.
     */
    public ExplorationResult exploreByPattern(
            String seed,
            RelationConfig relationConfig,
            ExploreOptions opts) throws IOException {
        return exploreByPattern(
            seed,
            relationConfig.name(),
            relationConfig.getFullPattern(seed),
            relationConfig.collocateReversePattern(),
            opts);
    }

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

        if (seed == null || seed.isEmpty()) {
            return ExplorationResult.empty(seed);
        }

        seed = seed.toLowerCase().trim();

        logger.debug("\n=== SEMANTIC FIELD EXPLORATION ===");
        logger.debug("Seed: {}", seed);
        logger.debug("Relation: {}", relationName);
        logger.debug("Pattern: {}", bcqlPattern);
        logger.debug("Parameters: top={}, nounsPerRel={}, minShared={}, minLogDice={}", topPredicates, nounsPerPredicate, minShared, minLogDice);
        logger.debug("------------------------------------------------------------");

        // Step 1: Get predicates/collocates for the seed noun using the BCQL pattern
        logger.debug("\nStep 1: Finding collocates for '{}'...", seed);

        List<QueryResults.WordSketchResult> seedRelations = fetchSeedCollocates(
            seed, bcqlPattern, simplePattern, minLogDice, topPredicates);

        if (seedRelations.isEmpty()) {
            logger.debug("  Still no results. Seed may be too rare.");
            return ExplorationResult.empty(seed);
        }

        logger.debug("  Found {} collocates:", seedRelations.size());
        seedRelations.forEach(a -> logger.debug("    {} (logDice={})", a.lemma(), String.format("%.2f", a.logDice())));

        // Build map: collocate -> logDice with seed
        Map<String, Double> seedCollocScores = new LinkedHashMap<>();
        Map<String, Long> seedCollocFrequencies = new LinkedHashMap<>();
        for (QueryResults.WordSketchResult r : seedRelations) {
            seedCollocScores.put(r.lemma().toLowerCase(), r.logDice());
            seedCollocFrequencies.put(r.lemma().toLowerCase(), r.frequency());
        }

        // Step 2: For each collocate, find nouns it collocates with
        logger.debug("\nStep 2: Finding nouns for each collocate...");
        Map<String, Map<String, Double>> nounProfiles = buildCollocateIndex(
            seedCollocScores, seed, minLogDice, nounsPerPredicate);
        logger.debug("  Total candidate nouns: {}", nounProfiles.size());

        // Step 3: Score nouns by shared collocate count
        logger.debug("\nStep 3: Scoring nouns by shared {}...", relationName);
        List<DiscoveredNoun> discoveredNouns = rankByLogDice(nounProfiles, minShared);
        discoveredNouns.sort((a, b) -> Double.compare(b.similarityScore(), a.similarityScore()));
        logger.debug("  Nouns with {}+ shared: {}", minShared, discoveredNouns.size());

        // Step 4: Identify core collocates
        List<CoreCollocate> coreCollocates = identifyCoreCollocates(seedCollocScores, discoveredNouns);

        // Print results
        logger.debug("\n--- RESULTS ---");
        logger.debug("\nSemantic class (nouns similar to '{}'):", seed);
        discoveredNouns.stream().limit(15).forEach(n ->
            logger.debug("  {} (shared={}, score={}) <- {}", n.noun(), n.sharedCount(),
                String.format("%.1f", n.similarityScore()), String.join(", ", n.sharedCollocates().keySet())));

        logger.debug("\nCore {} (define the class):", relationName);
        coreCollocates.stream().limit(10).forEach(a ->
            logger.debug("  {} (in {}/{} nouns, avgLogDice={})", a.collocate(), a.sharedByCount(),
                a.totalNouns(), String.format("%.1f", a.avgLogDice())));

        logger.debug("------------------------------------------------------------");

        return new ExplorationResult(seed, seedCollocScores, seedCollocFrequencies, discoveredNouns, coreCollocates);
    }

    // ==================== EXPLORATION PHASE HELPERS ====================

    /**
     * Phase 1: Fetch seed collocates using the BCQL pattern, with fallback to simplePattern.
     */
    private List<QueryResults.WordSketchResult> fetchSeedCollocates(
            String seed, String bcqlPattern, String simplePattern,
            double minLogDice, int topPredicates) throws IOException {
        List<QueryResults.WordSketchResult> results = executor.executeSurfacePattern(
            seed, bcqlPattern, minLogDice, topPredicates);
        if (results.isEmpty()) {
            logger.debug("  No results found for seed word. Trying fallback to simple pattern...");
            results = executor.findCollocations(seed, simplePattern, minLogDice, topPredicates);
        }
        return results;
    }

    /**
     * Phase 2: For each collocate, find nouns it collocates with (reverse lookup).
     * Returns a map of noun → {collocate → logDice}.
     */
    private Map<String, Map<String, Double>> buildCollocateIndex(
            Map<String, Double> seedCollocScores, String seed,
            double minLogDice, int nounsPerPredicate) throws IOException {
        Map<String, Map<String, Double>> nounProfiles = new LinkedHashMap<>();
        for (String colloc : seedCollocScores.keySet()) {
            List<QueryResults.WordSketchResult> nouns = executor.findCollocations(
                colloc, NOUN_PATTERN, minLogDice, nounsPerPredicate);
            logger.debug("  {}: {} nouns", colloc, nouns.size());
            for (QueryResults.WordSketchResult r : nouns) {
                String noun = r.lemma().toLowerCase();
                if (noun.equals(seed)) continue;
                nounProfiles.computeIfAbsent(noun, k -> new LinkedHashMap<>())
                    .put(colloc, r.logDice());
            }
        }
        return nounProfiles;
    }

    /**
     * Phase 3: Score candidate nouns by how many shared collocates they have.
     * Nouns below {@code minShared} are filtered out.
     */
    private List<DiscoveredNoun> rankByLogDice(
            Map<String, Map<String, Double>> nounProfiles, int minShared) {
        List<DiscoveredNoun> discoveredNouns = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> entry : nounProfiles.entrySet()) {
            String noun = entry.getKey();
            Map<String, Double> collocScores = entry.getValue();
            int sharedCount = collocScores.size();
            if (sharedCount < minShared) continue;
            double cumulativeScore = collocScores.values().stream().mapToDouble(Double::doubleValue).sum();
            double avgLogDice = cumulativeScore / sharedCount;
            discoveredNouns.add(new DiscoveredNoun(
                noun, collocScores, sharedCount, cumulativeScore, avgLogDice, sharedCount * avgLogDice));
        }
        return discoveredNouns;
    }

    /**
     * Phase 4: Identify core collocates — those shared by enough discovered nouns.
     */
    private List<CoreCollocate> identifyCoreCollocates(
            Map<String, Double> seedCollocScores, List<DiscoveredNoun> discoveredNouns) {
        Map<String, Integer> collocFrequency = new LinkedHashMap<>();
        Map<String, Double> collocTotalScore = new LinkedHashMap<>();
        for (DiscoveredNoun dn : discoveredNouns) {
            for (Map.Entry<String, Double> colloc : dn.sharedCollocates().entrySet()) {
                collocFrequency.merge(colloc.getKey(), 1, Integer::sum);
                collocTotalScore.merge(colloc.getKey(), colloc.getValue(), Double::sum);
            }
        }
        int minNounsForCore = Math.max(2, discoveredNouns.size() / 3);
        List<CoreCollocate> coreCollocates = new ArrayList<>();
        for (String colloc : seedCollocScores.keySet()) {
            int freq = collocFrequency.getOrDefault(colloc, 0);
            if (freq >= minNounsForCore) {
                double totalScore = collocTotalScore.getOrDefault(colloc, 0.0);
                double seedScore = seedCollocScores.get(colloc);
                coreCollocates.add(new CoreCollocate(
                    colloc, freq, discoveredNouns.size(), seedScore, totalScore / Math.max(1, freq)));
            }
        }
        coreCollocates.sort((a, b) -> {
            int cmp = Integer.compare(b.sharedByCount(), a.sharedByCount());
            return cmp != 0 ? cmp : Double.compare(b.avgLogDice(), a.avgLogDice());
        });
        return coreCollocates;
    }

    // ==================== COMPARISON MODE ====================

    public CollocateProfileComparator getComparator() {
        return comparator;
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

        List<QueryResults.CollocateResult> results = executor.executeBcqlQuery(bcqlQuery, maxExamples);

        return results.stream()
            .map(QueryResults.CollocateResult::getSentence)
            .distinct()
            .limit(maxExamples)
            .collect(Collectors.toList());
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
                collocateSharedCount.merge(wsr.lemma(), 1, Integer::sum);
            }
        }

        int threshold = Math.min(minShared, seeds.size());
        Set<String> commonCollocates = new HashSet<>();
        for (Map.Entry<String, Integer> entry : collocateSharedCount.entrySet()) {
            if (entry.getValue() >= threshold) {
                commonCollocates.add(entry.getKey());
            }
        }

        Map<String, Double> seedCollocScores = new LinkedHashMap<>();
        Map<String, Long> seedCollocFreqs = new LinkedHashMap<>();
        for (List<QueryResults.WordSketchResult> collocs : seedCollocateMap.values()) {
            for (QueryResults.WordSketchResult wsr : collocs) {
                seedCollocScores.merge(wsr.lemma(), wsr.logDice(), Math::max);
                seedCollocFreqs.merge(wsr.lemma(), wsr.frequency(), Long::sum);
            }
        }

        int numSeeds = seeds.size();
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
            discoveredNounsList.add(new DiscoveredNoun(seed, sharedCollocs, count, sum, avg, count * avg));
        }

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

        return new ExplorationResult(
            String.join(",", seeds),
            seedCollocScores, seedCollocFreqs,
            discoveredNounsList, coreCollocatesList);
    }

}
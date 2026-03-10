package pl.marcinmilkowski.word_sketch.query;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Semantic Field Explorer - discovers semantic classes via shared adjective predicates.
 *
 * <h2>Exploration Mode (Bootstrap)</h2>
 * <p>Starting from a seed word, discovers similar words by:</p>
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
 * <h2>Comparison Mode</h2>
 * <p>Compares adjective profiles across a given set of nouns, showing
 * which adjectives are shared vs distinctive.</p>
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
     * @param seed The seed noun to explore from
     * @param topPredicates Max predicates to use for expansion
     * @param nounsPerPredicate Max nouns to fetch per predicate
     * @param minShared Min predicates a noun must share with seed
     * @param minLogDice Min logDice threshold
     * @param bcqlPattern BCQL pattern with headword placeholder (e.g., "[lemma=\"%s\"] []{0,5} [xpos=\"JJ.*\"]")
     * @param simplePattern Simple pattern for reverse lookup (e.g., "[xpos=\"JJ.*\"]")
     * @param relationName Human-readable relation name for logging
     * @param headPos 1-based position of head in pattern (from grammar config)
     * @param collocatePos 1-based position of collocate in pattern (from grammar config)
     * @return ExplorationResult with discovered semantic class
     */
    public ExplorationResult exploreByPattern(
            String seed,
            int topPredicates,
            int nounsPerPredicate,
            int minShared,
            double minLogDice,
            String bcqlPattern,
            String simplePattern,
            String relationName,
            int headPos,
            int collocatePos) throws IOException {

        if (seed == null || seed.isEmpty()) {
            return ExplorationResult.empty(seed);
        }

        seed = seed.toLowerCase().trim();

        logger.info("\n=== SEMANTIC FIELD EXPLORATION ===");
        logger.info("Seed: {}", seed);
        logger.info("Relation: {}", relationName);
        logger.info("Pattern: {}", bcqlPattern);
        logger.info("Head position: {}, Collocate position: {}", headPos, collocatePos);
        logger.info("Parameters: top={}, nounsPerRel={}, minShared={}, minLogDice={}", topPredicates, nounsPerPredicate, minShared, minLogDice);
        logger.info("------------------------------------------------------------");

        // Step 1: Get predicates/collocates for the seed noun using the BCQL pattern
        logger.info("\nStep 1: Finding collocates for '{}'...", seed);

        // Use executeSurfacePattern which properly handles labeled BCQL patterns
        List<QueryResults.WordSketchResult> seedRelations;
        seedRelations = executor.executeSurfacePattern(
                seed, bcqlPattern, headPos, collocatePos, minLogDice, topPredicates);

        if (seedRelations.isEmpty()) {
            logger.info("  No results found for seed word.");
            // Try fallback to simple pattern
            logger.info("  Trying fallback to simple pattern...");
            seedRelations = executor.findCollocations(
                seed, simplePattern, minLogDice, topPredicates);
        }

        if (seedRelations.isEmpty()) {
            logger.info("  Still no results. Seed may be too rare.");
            return ExplorationResult.empty(seed);
        }

        logger.info("  Found {} collocates:", seedRelations.size());
        seedRelations.forEach(a -> logger.info("    {} (logDice={})", a.getLemma(), String.format("%.2f", a.getLogDice())));

        // Build map: collocate -> logDice with seed
        Map<String, Double> seedCollocScores = new LinkedHashMap<>();
        for (QueryResults.WordSketchResult r : seedRelations) {
            seedCollocScores.put(r.getLemma().toLowerCase(), r.getLogDice());
        }

        // Step 2: For each collocate, find nouns it collocates with
        logger.info("\nStep 2: Finding nouns for each collocate...");

        // noun -> {collocate -> logDice}
        Map<String, Map<String, Double>> nounProfiles = new LinkedHashMap<>();

        for (String colloc : seedCollocScores.keySet()) {
            // For adjectives, find nouns they modify (reverse relation)
            List<QueryResults.WordSketchResult> nouns = executor.findCollocations(
                colloc, NOUN_PATTERN, minLogDice, nounsPerPredicate);

            logger.info("  {}: {} nouns", colloc, nouns.size());

            for (QueryResults.WordSketchResult r : nouns) {
                String noun = r.getLemma().toLowerCase();
                if (noun.equals(seed)) continue;

                nounProfiles
                    .computeIfAbsent(noun, k -> new LinkedHashMap<>())
                    .put(colloc, r.getLogDice());
            }
        }

        logger.info("  Total candidate nouns: {}", nounProfiles.size());

        // Step 3: Score nouns by shared collocate count
        logger.info("\nStep 3: Scoring nouns by shared {}...", relationName);

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

        logger.info("  Nouns with {}+ shared: {}", minShared, discoveredNouns.size());

        // Step 4: Identify core collocates

        Map<String, Integer> collocFrequency = new LinkedHashMap<>();
        Map<String, Double> collocTotalScore = new LinkedHashMap<>();

        for (DiscoveredNoun dn : discoveredNouns) {
            for (Map.Entry<String, Double> colloc : dn.sharedAdjectives.entrySet()) {
                collocFrequency.merge(colloc.getKey(), 1, Integer::sum);
                collocTotalScore.merge(colloc.getKey(), colloc.getValue(), Double::sum);
            }
        }

        List<CoreAdjective> coreCollocates = new ArrayList<>();
        int minNounsForCore = Math.max(2, discoveredNouns.size() / 3);

        for (String colloc : seedCollocScores.keySet()) {
            int freq = collocFrequency.getOrDefault(colloc, 0);
            double totalScore = collocTotalScore.getOrDefault(colloc, 0.0);
            double seedScore = seedCollocScores.get(colloc);

            if (freq >= minNounsForCore || freq >= 2) {
                coreCollocates.add(new CoreAdjective(
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
        logger.info("\n--- RESULTS ---");
        logger.info("\nSemantic class (nouns similar to '{}'):", seed);
        discoveredNouns.stream().limit(15).forEach(n ->
            logger.info("  {} (shared={}, score={}) <- {}", n.noun, n.sharedCount,
                String.format("%.1f", n.similarityScore), String.join(", ", n.sharedAdjectives.keySet())));

        logger.info("\nCore {} (define the class):", relationName);
        coreCollocates.stream().limit(10).forEach(a ->
            logger.info("  {} (in {}/{} nouns, avgLogDice={})", a.adjective, a.sharedByCount,
                a.totalNouns, String.format("%.1f", a.avgLogDice)));

        logger.info("------------------------------------------------------------");

        return new ExplorationResult(seed, seedCollocScores, discoveredNouns, coreCollocates);
    }

    // ==================== COMPARISON MODE ====================

    /**
     * Compare adjective profiles across a set of seed nouns.
     *
     * @param seedNouns Nouns to compare (e.g., "theory", "model", "hypothesis")
     * @param minLogDice Minimum logDice score for adjective-noun pairs
     * @param maxPerNoun Maximum adjectives to retrieve per noun
     * @return ComparisonResult with graded adjective profiles
     */
    public ComparisonResult compare(
            Set<String> seedNouns,
            double minLogDice,
            int maxPerNoun) throws IOException {

        if (seedNouns == null || seedNouns.isEmpty()) {
            return ComparisonResult.empty();
        }

        List<String> nounList = new ArrayList<>(seedNouns);
        int nounCount = nounList.size();

        logger.info("\n=== SEMANTIC FIELD COMPARISON ===");
        logger.info("Nouns: {}", seedNouns);
        logger.info("Min logDice: {}", minLogDice);
        logger.info("------------------------------------------------------------");

        // Phase 1: Build adjective profiles for each noun
        // adjective -> {noun -> logDice}
        Map<String, Map<String, Double>> adjectiveProfiles = new LinkedHashMap<>();

        for (String noun : nounList) {
            logger.info("\nProfiling: {}", noun);

            List<QueryResults.WordSketchResult> adjectives = executor.findCollocations(
                noun, ADJECTIVE_PATTERN, minLogDice, maxPerNoun);

            logger.info("  Found {} adjectives", adjectives.size());
            if (!adjectives.isEmpty()) {
                logger.info("  Top 5: {}", adjectives.subList(0, Math.min(5, adjectives.size())));
            }

            for (QueryResults.WordSketchResult r : adjectives) {
                String adj = r.getLemma().toLowerCase();
                adjectiveProfiles
                    .computeIfAbsent(adj, k -> new LinkedHashMap<>())
                    .put(noun, r.getLogDice());
            }
        }

        // Phase 2: Build comparison profiles with graded scores
        logger.info("\n--- Building Comparison Profiles ---");

        List<AdjectiveProfile> profiles = new ArrayList<>();

        for (Map.Entry<String, Map<String, Double>> entry : adjectiveProfiles.entrySet()) {
            String adj = entry.getKey();
            Map<String, Double> nounScores = entry.getValue();

            // Create full score map (0 for missing nouns)
            Map<String, Double> fullScores = new LinkedHashMap<>();
            for (String noun : nounList) {
                fullScores.put(noun, nounScores.getOrDefault(noun, 0.0));
            }

            int presentIn = nounScores.size();
            double[] scores = nounScores.values().stream().mapToDouble(Double::doubleValue).toArray();
            double avgScore = Arrays.stream(scores).average().orElse(0.0);
            double maxScore = Arrays.stream(scores).max().orElse(0.0);
            double minScore = Arrays.stream(scores).min().orElse(0.0);

            // Variance calculation
            double variance = 0.0;
            if (scores.length > 1) {
                for (double s : scores) {
                    variance += (s - avgScore) * (s - avgScore);
                }
                variance /= scores.length;
            }

            // Commonality score: rewards adjectives shared by many nouns with high scores
            double commonalityScore = presentIn * avgScore;

            // Distinctiveness score: rewards adjectives specific to few nouns with high max score
            // Also considers variance - high variance = more distinctive usage pattern
            double distinctivenessScore = maxScore * (1.0 - (double) presentIn / nounCount)
                                         + Math.sqrt(variance);

            profiles.add(new AdjectiveProfile(
                adj, fullScores, presentIn, nounCount,
                avgScore, maxScore, minScore, variance,
                commonalityScore, distinctivenessScore
            ));
        }

        // Sort by commonality score (most shared first)
        profiles.sort((a, b) -> Double.compare(b.commonalityScore, a.commonalityScore));

        logger.info("Total unique adjectives: {}", profiles.size());

        // Show top shared
        logger.info("\nTop SHARED (high commonality):");
        profiles.stream()
            .filter(p -> p.presentInCount >= 2)
            .limit(10)
            .forEach(p -> logger.info("  {} (in {}/{} nouns, avg={})", p.adjective,
                p.presentInCount, p.totalNouns, String.format("%.2f", p.avgLogDice)));

        // Show top distinctive
        logger.info("\nTop DISTINCTIVE (specific to 1 noun):");
        profiles.stream()
            .filter(p -> p.presentInCount == 1)
            .sorted((a, b) -> Double.compare(b.maxLogDice, a.maxLogDice))
            .limit(10)
            .forEach(p -> {
                String specificNoun = p.nounScores.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .findFirst().orElse("?");
                logger.info("  {} -> {} ({})", p.adjective, specificNoun, String.format("%.2f", p.maxLogDice));
            });

        logger.info("------------------------------------------------------------");

        return new ComparisonResult(nounList, profiles);
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

    // ============ Result Classes ============

    /**
     * Complete comparison result with graded adjective profiles.
     */
    public static class ComparisonResult {
        private final List<String> nouns;
        private final List<AdjectiveProfile> adjectives;

        public ComparisonResult(List<String> nouns, List<AdjectiveProfile> adjectives) {
            this.nouns = nouns;
            this.adjectives = adjectives;
        }

        public static ComparisonResult empty() {
            return new ComparisonResult(List.of(), List.of());
        }

        public List<String> getNouns() { return nouns; }
        public List<AdjectiveProfile> getAllAdjectives() { return adjectives; }

        /** Adjectives shared by ALL nouns */
        public List<AdjectiveProfile> getFullyShared() {
            return adjectives.stream()
                .filter(a -> a.presentInCount == nouns.size())
                .collect(Collectors.toList());
        }

        /** Adjectives shared by 2+ nouns but not all */
        public List<AdjectiveProfile> getPartiallyShared() {
            return adjectives.stream()
                .filter(a -> a.presentInCount >= 2 && a.presentInCount < nouns.size())
                .collect(Collectors.toList());
        }

        /** Adjectives specific to exactly one noun */
        public List<AdjectiveProfile> getSpecific() {
            return adjectives.stream()
                .filter(a -> a.presentInCount == 1)
                .collect(Collectors.toList());
        }

        /** Get adjectives specific to a particular noun */
        public List<AdjectiveProfile> getSpecificTo(String noun) {
            return adjectives.stream()
                .filter(a -> a.presentInCount == 1 && a.nounScores.getOrDefault(noun, 0.0) > 0)
                .sorted((x, y) -> Double.compare(y.maxLogDice, x.maxLogDice))
                .collect(Collectors.toList());
        }

        /** Build edges for visualization */
        public List<Edge> getEdges() {
            List<Edge> edges = new ArrayList<>();
            for (AdjectiveProfile adj : adjectives) {
                for (Map.Entry<String, Double> entry : adj.nounScores.entrySet()) {
                    if (entry.getValue() > 0) {
                        edges.add(new Edge(adj.adjective, entry.getKey(),
                            entry.getValue(), "modifier"));
                    }
                }
            }
            return edges;
        }

        @Override
        public String toString() {
            int shared = (int) adjectives.stream().filter(a -> a.presentInCount >= 2).count();
            int specific = (int) adjectives.stream().filter(a -> a.presentInCount == 1).count();
            return String.format("ComparisonResult(%d nouns, %d adjectives: %d shared, %d specific)",
                nouns.size(), adjectives.size(), shared, specific);
        }
    }

    /**
     * Profile of one adjective across all seed nouns.
     * Shows graded scores, not just binary presence.
     */
    public static class AdjectiveProfile {
        public final String adjective;
        public final Map<String, Double> nounScores;  // noun -> logDice (0 if absent)
        public final int presentInCount;              // How many nouns have this adjective
        public final int totalNouns;                  // Total seed nouns
        public final double avgLogDice;               // Average score (where present)
        public final double maxLogDice;               // Highest score
        public final double minLogDice;               // Lowest score (where present)
        public final double variance;                 // Score variance (high = distinctive pattern)
        public final double commonalityScore;         // For ranking shared adjectives
        public final double distinctivenessScore;     // For ranking specific adjectives

        public AdjectiveProfile(String adjective, Map<String, Double> nounScores,
                int presentInCount, int totalNouns,
                double avgLogDice, double maxLogDice, double minLogDice, double variance,
                double commonalityScore, double distinctivenessScore) {
            this.adjective = adjective;
            this.nounScores = nounScores;
            this.presentInCount = presentInCount;
            this.totalNouns = totalNouns;
            this.avgLogDice = avgLogDice;
            this.maxLogDice = maxLogDice;
            this.minLogDice = minLogDice;
            this.variance = variance;
            this.commonalityScore = commonalityScore;
            this.distinctivenessScore = distinctivenessScore;
        }

        public boolean isFullyShared() { return presentInCount == totalNouns; }
        public boolean isPartiallyShared() { return presentInCount >= 2 && presentInCount < totalNouns; }
        public boolean isSpecific() { return presentInCount == 1; }

        /** Get the noun this adjective is most associated with */
        public String getStrongestNoun() {
            return nounScores.entrySet().stream()
                .max(Comparator.comparingDouble(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);
        }

        @Override
        public String toString() {
            String scoreStr = nounScores.entrySet().stream()
                .map(e -> e.getKey() + ":" + String.format("%.1f", e.getValue()))
                .collect(Collectors.joining(", "));
            return String.format("%s [%d/%d: %s]", adjective, presentInCount, totalNouns, scoreStr);
        }
    }

    /**
     * Edge for graph visualization.
     */
    public static class Edge {
        public final String source;   // adjective
        public final String target;   // noun
        public final double weight;   // logDice score
        public final String type;

        public Edge(String source, String target, double weight, String type) {
            this.source = source;
            this.target = target;
            this.weight = weight;
            this.type = type;
        }
    }

    // ============ Exploration Result Classes ============

    /**
     * Result of semantic field exploration from a seed word.
     */
    public static class ExplorationResult {
        public final String seed;
        public final Map<String, Double> seedAdjectives;  // adjective -> logDice with seed
        public final List<DiscoveredNoun> discoveredNouns;
        public final List<CoreAdjective> coreAdjectives;

        public ExplorationResult(String seed, Map<String, Double> seedAdjectives,
                List<DiscoveredNoun> discoveredNouns, List<CoreAdjective> coreAdjectives) {
            this.seed = seed;
            this.seedAdjectives = seedAdjectives;
            this.discoveredNouns = discoveredNouns;
            this.coreAdjectives = coreAdjectives;
        }

        public static ExplorationResult empty(String seed) {
            return new ExplorationResult(seed, Map.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return discoveredNouns.isEmpty();
        }

        /** Get top N similar nouns */
        public List<DiscoveredNoun> getTopNouns(int n) {
            return discoveredNouns.stream().limit(n).collect(Collectors.toList());
        }

        /** Get nouns sharing at least minShared adjectives with seed */
        public List<DiscoveredNoun> getNounsWithMinShared(int minShared) {
            return discoveredNouns.stream()
                .filter(n -> n.sharedCount >= minShared)
                .collect(Collectors.toList());
        }

        /** Build edges for visualization */
        public List<Edge> getEdges() {
            List<Edge> edges = new ArrayList<>();

            // Edges from seed to its adjectives
            for (Map.Entry<String, Double> adj : seedAdjectives.entrySet()) {
                edges.add(new Edge(seed, adj.getKey(), adj.getValue(), "seed_adj"));
            }

            // Edges from discovered nouns to shared adjectives
            for (DiscoveredNoun noun : discoveredNouns) {
                for (Map.Entry<String, Double> adj : noun.sharedAdjectives.entrySet()) {
                    edges.add(new Edge(noun.noun, adj.getKey(), adj.getValue(), "discovered_adj"));
                }
            }

            return edges;
        }

        @Override
        public String toString() {
            return String.format("ExplorationResult(seed='%s', adjectives=%d, discovered=%d, core=%d)",
                seed, seedAdjectives.size(), discoveredNouns.size(), coreAdjectives.size());
        }
    }

    /**
     * A noun discovered during exploration - shares adjectives with the seed.
     */
    public static class DiscoveredNoun {
        public final String noun;
        public final Map<String, Double> sharedAdjectives;  // adjective -> logDice
        public final int sharedCount;                        // Number of shared adjectives
        public final double cumulativeScore;                 // Sum of logDice scores
        public final double avgLogDice;                      // Average logDice
        public final double similarityScore;                 // Ranking score (sharedCount × avgLogDice)

        public DiscoveredNoun(String noun, Map<String, Double> sharedAdjectives,
                int sharedCount, double cumulativeScore, double avgLogDice, double similarityScore) {
            this.noun = noun;
            this.sharedAdjectives = sharedAdjectives;
            this.sharedCount = sharedCount;
            this.cumulativeScore = cumulativeScore;
            this.avgLogDice = avgLogDice;
            this.similarityScore = similarityScore;
        }

        public List<String> getSharedAdjectiveList() {
            return new ArrayList<>(sharedAdjectives.keySet());
        }

        @Override
        public String toString() {
            return String.format("%s (shared=%d, score=%.1f)", noun, sharedCount, similarityScore);
        }
    }

    /**
     * An adjective that defines the semantic class (shared by multiple discovered nouns).
     */
    public static class CoreAdjective {
        public final String adjective;
        public final int sharedByCount;    // How many discovered nouns share this adjective
        public final int totalNouns;       // Total discovered nouns
        public final double seedLogDice;   // LogDice with the seed word
        public final double avgLogDice;    // Average logDice across discovered nouns

        public CoreAdjective(String adjective, int sharedByCount, int totalNouns,
                double seedLogDice, double avgLogDice) {
            this.adjective = adjective;
            this.sharedByCount = sharedByCount;
            this.totalNouns = totalNouns;
            this.seedLogDice = seedLogDice;
            this.avgLogDice = avgLogDice;
        }

        /** Coverage ratio: how many of the discovered nouns share this adjective */
        public double getCoverage() {
            return totalNouns > 0 ? (double) sharedByCount / totalNouns : 0.0;
        }

        @Override
        public String toString() {
            return String.format("%s (in %d/%d nouns, avgLogDice=%.1f)",
                adjective, sharedByCount, totalNouns, avgLogDice);
        }
    }
}

package pl.marcinmilkowski.word_sketch.query;

import pl.marcinmilkowski.word_sketch.query.WordSketchQueryExecutor.WordSketchResult;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

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

    private final QueryExecutor executor;
    
    // Patterns for finding collocates by POS
    private static final String ADJECTIVE_PATTERN = "[tag=jj.*]";
    private static final String NOUN_PATTERN = "[tag=nn.*]";

    public SemanticFieldExplorer(String indexPath) throws IOException {
        this.executor = QueryExecutorFactory.createAutoDetect(indexPath);
    }
    
    // Constructor for testing with mock executor
    public SemanticFieldExplorer(QueryExecutor executor) {
        this.executor = executor;
    }

    // ==================== EXPLORATION MODE ====================

    /**
     * Explore semantic field using adjectival predicates (default mode).
     * Uses grammatical patterns like "X is/seems/appears ADJ" to find
     * shared properties, which works better for abstract nouns.
     * 
     * @param seed The seed noun to explore from
     * @param topPredicates Max predicates to use for expansion
     * @param nounsPerPredicate Max nouns to fetch per predicate
     * @param minShared Min predicates a noun must share with seed
     * @param minLogDice Min logDice threshold
     * @param relationType The type of grammatical relation to use
     * @return ExplorationResult with discovered semantic class
     */
    public ExplorationResult exploreByRelation(
            String seed,
            int topPredicates,
            int nounsPerPredicate,
            int minShared,
            double minLogDice,
            QueryExecutor.RelationType relationType) throws IOException {
        
        if (seed == null || seed.isEmpty()) {
            return ExplorationResult.empty(seed);
        }
        
        seed = seed.toLowerCase().trim();
        
        System.out.println("\n=== SEMANTIC FIELD EXPLORATION (by " + relationType + ") ===");
        System.out.println("Seed: " + seed);
        System.out.println("Relation: " + relationType);
        System.out.println("Parameters: top=" + topPredicates + 
            ", nounsPerRel=" + nounsPerPredicate + 
            ", minShared=" + minShared +
            ", minLogDice=" + minLogDice);
        System.out.println("-".repeat(60));
        
        // Step 1: Get predicates/collocates for the seed noun using the specified relation
        System.out.println("\nStep 1: Finding " + relationType + " for '" + seed + "'...");
        
        List<WordSketchResult> seedRelations = executor.findGrammaticalRelation(
            seed, relationType, minLogDice, topPredicates);
        
        if (seedRelations.isEmpty()) {
            System.out.println("  No " + relationType + " found for seed word.");
            // Try fallback to simple pattern
            System.out.println("  Trying fallback to simple pattern...");
            seedRelations = executor.findCollocations(
                seed, relationType.getSimplePattern(), minLogDice, topPredicates);
        }
        
        if (seedRelations.isEmpty()) {
            System.out.println("  Still no results. Seed may be too rare.");
            return ExplorationResult.empty(seed);
        }
        
        System.out.println("  Found " + seedRelations.size() + " collocates:");
        seedRelations.forEach(a -> System.out.println("    " + a.getLemma() + 
            " (logDice=" + String.format("%.2f", a.getLogDice()) + ")"));
        
        // Build map: collocate -> logDice with seed
        Map<String, Double> seedCollocScores = new LinkedHashMap<>();
        for (WordSketchResult r : seedRelations) {
            seedCollocScores.put(r.getLemma().toLowerCase(), r.getLogDice());
        }
        
        // Step 2: For each collocate, find nouns it collocates with
        System.out.println("\nStep 2: Finding nouns for each collocate...");
        
        // noun -> {collocate -> logDice}
        Map<String, Map<String, Double>> nounProfiles = new LinkedHashMap<>();
        
        for (String colloc : seedCollocScores.keySet()) {
            // For adjectives, find nouns they modify (reverse relation)
            List<WordSketchResult> nouns = executor.findCollocations(
                colloc, NOUN_PATTERN, minLogDice, nounsPerPredicate);
            
            System.out.println("  " + colloc + ": " + nouns.size() + " nouns");
            
            for (WordSketchResult r : nouns) {
                String noun = r.getLemma().toLowerCase();
                if (noun.equals(seed)) continue;
                
                nounProfiles
                    .computeIfAbsent(noun, k -> new LinkedHashMap<>())
                    .put(colloc, r.getLogDice());
            }
        }
        
        System.out.println("  Total candidate nouns: " + nounProfiles.size());
        
        // Step 3: Score nouns by shared collocate count
        System.out.println("\nStep 3: Scoring nouns by shared " + relationType + "...");
        
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
        
        System.out.println("  Nouns with " + minShared + "+ shared: " + discoveredNouns.size());
        
        // Step 4: Identify core collocates
        System.out.println("\nStep 4: Identifying core " + relationType + "...");
        
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
        System.out.println("\n--- RESULTS ---");
        System.out.println("\nSemantic class (nouns similar to '" + seed + "'):");
        discoveredNouns.stream().limit(15).forEach(n -> 
            System.out.println("  " + n.noun + " (shared=" + n.sharedCount + 
                ", score=" + String.format("%.1f", n.similarityScore) + 
                ") <- " + String.join(", ", n.sharedAdjectives.keySet())));
        
        System.out.println("\nCore " + relationType + " (define the class):");
        coreCollocates.stream().limit(10).forEach(a -> 
            System.out.println("  " + a.adjective + " (in " + a.sharedByCount + 
                "/" + a.totalNouns + " nouns, avgLogDice=" + 
                String.format("%.1f", a.avgLogDice) + ")"));
        
        System.out.println("-".repeat(60));
        
        return new ExplorationResult(seed, seedCollocScores, discoveredNouns, coreCollocates);
    }

    /**
     * Explore using adjectival predicates (best for abstract nouns like theory, model).
     */
    public ExplorationResult exploreByPredicates(
            String seed,
            int topPredicates,
            int nounsPerPredicate,
            int minShared,
            double minLogDice) throws IOException {
        return exploreByRelation(seed, topPredicates, nounsPerPredicate, minShared, 
            minLogDice, QueryExecutor.RelationType.ADJ_PREDICATE);
    }

    /**
     * Explore semantic field by bootstrapping from a seed word.
     * Uses simple adjective modifiers (faster, but less accurate for abstract nouns).
     * 
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Find top adjectives that modify the seed noun</li>
     *   <li>For each adjective, find other nouns it modifies</li>
     *   <li>Score nouns by shared adjective count and cumulative logDice</li>
     *   <li>Return semantic class (similar nouns) and core adjectives</li>
     * </ol>
     * 
     * @param seed The seed noun to explore from (e.g., "house")
     * @param topAdjectives Max adjectives to use for expansion (default: 15)
     * @param nounsPerAdjective Max nouns to fetch per adjective (default: 30)
     * @param minSharedAdjectives Min adjectives a noun must share with seed (default: 2)
     * @param minLogDice Min logDice threshold for collocations
     * @return ExplorationResult with discovered semantic class
     */
    public ExplorationResult explore(
            String seed,
            int topAdjectives,
            int nounsPerAdjective,
            int minSharedAdjectives,
            double minLogDice) throws IOException {
        
        if (seed == null || seed.isEmpty()) {
            return ExplorationResult.empty(seed);
        }
        
        seed = seed.toLowerCase().trim();
        
        System.out.println("\n=== SEMANTIC FIELD EXPLORATION ===");
        System.out.println("Seed: " + seed);
        System.out.println("Parameters: topAdj=" + topAdjectives + 
            ", nounsPerAdj=" + nounsPerAdjective + 
            ", minShared=" + minSharedAdjectives +
            ", minLogDice=" + minLogDice);
        System.out.println("-".repeat(60));
        
        // Step 1: Get adjectives for the seed noun
        System.out.println("\nStep 1: Finding adjectives for '" + seed + "'...");
        List<WordSketchResult> seedAdjectives = executor.findCollocations(
            seed, ADJECTIVE_PATTERN, minLogDice, topAdjectives);
        
        if (seedAdjectives.isEmpty()) {
            System.out.println("  No adjectives found for seed word.");
            return ExplorationResult.empty(seed);
        }
        
        System.out.println("  Found " + seedAdjectives.size() + " adjectives:");
        seedAdjectives.forEach(a -> System.out.println("    " + a.getLemma() + 
            " (logDice=" + String.format("%.2f", a.getLogDice()) + ")"));
        
        // Build map: adjective -> logDice with seed
        Map<String, Double> seedAdjScores = new LinkedHashMap<>();
        for (WordSketchResult r : seedAdjectives) {
            seedAdjScores.put(r.getLemma().toLowerCase(), r.getLogDice());
        }
        
        // Step 2: For each adjective, find nouns it collocates with
        System.out.println("\nStep 2: Finding nouns for each adjective...");
        
        // noun -> {adjective -> logDice}
        Map<String, Map<String, Double>> nounProfiles = new LinkedHashMap<>();
        
        for (String adj : seedAdjScores.keySet()) {
            List<WordSketchResult> nouns = executor.findCollocations(
                adj, NOUN_PATTERN, minLogDice, nounsPerAdjective);
            
            System.out.println("  " + adj + ": " + nouns.size() + " nouns");
            
            for (WordSketchResult r : nouns) {
                String noun = r.getLemma().toLowerCase();
                // Skip the seed itself
                if (noun.equals(seed)) continue;
                
                nounProfiles
                    .computeIfAbsent(noun, k -> new LinkedHashMap<>())
                    .put(adj, r.getLogDice());
            }
        }
        
        System.out.println("  Total candidate nouns: " + nounProfiles.size());
        
        // Step 3: Score nouns by shared adjective count and cumulative score
        System.out.println("\nStep 3: Scoring nouns by shared adjectives...");
        
        List<DiscoveredNoun> discoveredNouns = new ArrayList<>();
        
        for (Map.Entry<String, Map<String, Double>> entry : nounProfiles.entrySet()) {
            String noun = entry.getKey();
            Map<String, Double> adjScores = entry.getValue();
            
            int sharedCount = adjScores.size();
            if (sharedCount < minSharedAdjectives) continue;
            
            // Calculate cumulative score: sum of logDice for shared adjectives
            double cumulativeScore = adjScores.values().stream()
                .mapToDouble(Double::doubleValue)
                .sum();
            
            // Calculate similarity score: shared count * average logDice
            double avgLogDice = cumulativeScore / sharedCount;
            double similarityScore = sharedCount * avgLogDice;
            
            discoveredNouns.add(new DiscoveredNoun(
                noun, adjScores, sharedCount, 
                cumulativeScore, avgLogDice, similarityScore
            ));
        }
        
        // Sort by similarity score (shared count × avg logDice)
        discoveredNouns.sort((a, b) -> Double.compare(b.similarityScore, a.similarityScore));
        
        System.out.println("  Nouns with " + minSharedAdjectives + "+ shared adjectives: " + 
            discoveredNouns.size());
        
        // Step 4: Identify core adjectives (shared by many discovered nouns)
        System.out.println("\nStep 4: Identifying core adjectives...");
        
        Map<String, Integer> adjFrequency = new LinkedHashMap<>();
        Map<String, Double> adjTotalScore = new LinkedHashMap<>();
        
        for (DiscoveredNoun dn : discoveredNouns) {
            for (Map.Entry<String, Double> adj : dn.sharedAdjectives.entrySet()) {
                String adjective = adj.getKey();
                adjFrequency.merge(adjective, 1, Integer::sum);
                adjTotalScore.merge(adjective, adj.getValue(), Double::sum);
            }
        }
        
        // Core adjectives: those shared by multiple discovered nouns
        List<CoreAdjective> coreAdjectives = new ArrayList<>();
        int minNounsForCore = Math.max(2, discoveredNouns.size() / 3);
        
        for (String adj : seedAdjScores.keySet()) {
            int freq = adjFrequency.getOrDefault(adj, 0);
            double totalScore = adjTotalScore.getOrDefault(adj, 0.0);
            double seedScore = seedAdjScores.get(adj);
            
            if (freq >= minNounsForCore || freq >= 2) {
                coreAdjectives.add(new CoreAdjective(
                    adj, freq, discoveredNouns.size(), 
                    seedScore, totalScore / Math.max(1, freq)
                ));
            }
        }
        
        coreAdjectives.sort((a, b) -> {
            int cmp = Integer.compare(b.sharedByCount, a.sharedByCount);
            return cmp != 0 ? cmp : Double.compare(b.avgLogDice, a.avgLogDice);
        });
        
        // Print results
        System.out.println("\n--- RESULTS ---");
        System.out.println("\nSemantic class (nouns similar to '" + seed + "'):");
        discoveredNouns.stream().limit(15).forEach(n -> 
            System.out.println("  " + n.noun + " (shared=" + n.sharedCount + 
                ", score=" + String.format("%.1f", n.similarityScore) + 
                ") <- " + String.join(", ", n.sharedAdjectives.keySet())));
        
        System.out.println("\nCore adjectives (define the class):");
        coreAdjectives.stream().limit(10).forEach(a -> 
            System.out.println("  " + a.adjective + " (in " + a.sharedByCount + 
                "/" + a.totalNouns + " nouns, avgLogDice=" + 
                String.format("%.1f", a.avgLogDice) + ")"));
        
        System.out.println("-".repeat(60));
        
        return new ExplorationResult(seed, seedAdjScores, discoveredNouns, coreAdjectives);
    }
    
    /**
     * Explore with default parameters.
     */
    public ExplorationResult explore(String seed) throws IOException {
        return explore(seed, 15, 30, 2, 5.0);
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
        
        System.out.println("\n=== SEMANTIC FIELD COMPARISON ===");
        System.out.println("Nouns: " + seedNouns);
        System.out.println("Min logDice: " + minLogDice);
        System.out.println("-".repeat(60));
        
        // Phase 1: Build adjective profiles for each noun
        // adjective -> {noun -> logDice}
        Map<String, Map<String, Double>> adjectiveProfiles = new LinkedHashMap<>();
        
        for (String noun : nounList) {
            System.out.println("\nProfiling: " + noun);
            
            List<WordSketchResult> adjectives = executor.findCollocations(
                noun, ADJECTIVE_PATTERN, minLogDice, maxPerNoun);
            
            System.out.println("  Found " + adjectives.size() + " adjectives");
            if (!adjectives.isEmpty()) {
                System.out.println("  Top 5: " + adjectives.stream()
                    .limit(5)
                    .map(a -> a.getLemma() + "(" + String.format("%.1f", a.getLogDice()) + ")")
                    .collect(Collectors.joining(", ")));
            }
            
            for (WordSketchResult r : adjectives) {
                String adj = r.getLemma().toLowerCase();
                adjectiveProfiles
                    .computeIfAbsent(adj, k -> new LinkedHashMap<>())
                    .put(noun, r.getLogDice());
            }
        }
        
        // Phase 2: Build comparison profiles with graded scores
        System.out.println("\n--- Building Comparison Profiles ---");
        
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
        
        System.out.println("Total unique adjectives: " + profiles.size());
        
        // Show top shared
        System.out.println("\nTop SHARED (high commonality):");
        profiles.stream()
            .filter(p -> p.presentInCount >= 2)
            .limit(10)
            .forEach(p -> System.out.println("  " + p.adjective + 
                " (" + p.presentInCount + "/" + nounCount + ", avg=" + 
                String.format("%.1f", p.avgLogDice) + ")"));
        
        // Show top distinctive
        System.out.println("\nTop DISTINCTIVE (specific to 1 noun):");
        profiles.stream()
            .filter(p -> p.presentInCount == 1)
            .sorted((a, b) -> Double.compare(b.maxLogDice, a.maxLogDice))
            .limit(10)
            .forEach(p -> {
                String specificNoun = p.nounScores.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .findFirst().orElse("?");
                System.out.println("  " + p.adjective + " -> " + specificNoun + 
                    " (logDice=" + String.format("%.1f", p.maxLogDice) + ")");
            });
        
        System.out.println("-".repeat(60));
        
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
        // Build CQL query: adjective near noun
        // e.g., [lemma="good" & tag="JJ.*"] []{0,3} [lemma="theory" & tag="N.*"]
        String cqlQuery = String.format(
            "[lemma=\"%s\" & tag=\"JJ.*\"] []{0,3} [lemma=\"%s\" & tag=\"N.*\"]",
            adjective, noun
        );
        
        List<WordSketchQueryExecutor.ConcordanceResult> results = executor.executeQuery(cqlQuery, maxExamples);
        
        return results.stream()
            .map(WordSketchQueryExecutor.ConcordanceResult::getSentence)
            .distinct()
            .limit(maxExamples)
            .collect(Collectors.toList());
    }

    @Override
    public void close() throws IOException {
        if (executor instanceof AutoCloseable) {
            try {
                ((AutoCloseable) executor).close();
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

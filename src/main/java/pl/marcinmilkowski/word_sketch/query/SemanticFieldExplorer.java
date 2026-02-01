package pl.marcinmilkowski.word_sketch.query;

import pl.marcinmilkowski.word_sketch.query.WordSketchQueryExecutor.WordSketchResult;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Semantic Field Explorer - compares adjective collocate profiles across related nouns.
 * 
 * <p>Unlike simple intersection, this explorer shows <b>graded comparison</b>:
 * for each adjective, we see how strongly it collocates with EACH noun in the set.
 * This reveals both shared properties AND distinctive properties.</p>
 * 
 * <h2>Example: Theoretical Concepts</h2>
 * <pre>
 * Seed nouns: [theory, model, hypothesis]
 * 
 * ADJECTIVE    | theory | model | hypothesis | Status
 * -------------|--------|-------|------------|--------
 * accurate     |   6.5  |  5.8  |    5.2     | SHARED (all 3)
 * testable     |   4.0  |   -   |    5.5     | PARTIAL (2/3)
 * mathematical |   3.2  |  6.8  |     -      | PARTIAL (2/3)  
 * falsifiable  |    -   |   -   |    7.2     | SPECIFIC (hypothesis only)
 * computational|    -   |  5.5  |     -      | SPECIFIC (model only)
 * </pre>
 * 
 * <h2>Scores</h2>
 * <ul>
 *   <li><b>Commonality:</b> presentInCount × avgLogDice - ranks adjectives shared by many nouns with high scores</li>
 *   <li><b>Distinctiveness:</b> maxLogDice × (1 - presentInCount/totalNouns) - ranks adjectives specific to few nouns</li>
 * </ul>
 * 
 * <h2>Computational Complexity</h2>
 * <p>O(n) queries where n = number of seed nouns. No expansion or recursion.</p>
 */
public class SemanticFieldExplorer implements AutoCloseable {

    private final WordSketchQueryExecutor executor;
    
    // Simple adjective pattern - find adjectives modifying nouns within a small window
    private static final String ADJECTIVE_PATTERN = "[tag=\"JJ.*\"]";

    public SemanticFieldExplorer(String indexPath) throws IOException {
        QueryExecutor exec = QueryExecutorFactory.createAutoDetect(indexPath);
        if (!(exec instanceof WordSketchQueryExecutor)) {
            throw new IllegalStateException("Expected WordSketchQueryExecutor, got: " + exec.getClass());
        }
        this.executor = (WordSketchQueryExecutor) exec;
    }
    
    // Constructor for testing with mock executor
    SemanticFieldExplorer(WordSketchQueryExecutor executor) {
        this.executor = executor;
    }

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
}

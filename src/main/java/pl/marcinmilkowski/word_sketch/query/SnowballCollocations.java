package pl.marcinmilkowski.word_sketch.query;

import pl.marcinmilkowski.word_sketch.query.WordSketchQueryExecutor.WordSketchResult;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Snowball collocation explorer - recursively traverses adjective-noun graph
 * to discover comprehensive sets of semantically related collocations.
 *
 * <p>The "snowball" metaphor: starting with seed adjectives, we collect nouns
 * they modify, then find new adjectives modifying those nouns, and repeat
 * until no new adjectives are discovered.</p>
 *
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * try (SnowballCollocations explorer = new SnowballCollocations(indexPath)) {
 *     // Explore adjectives modifying nouns (adjectives as PREDICATES)
 *     SnowballResult result = explorer.exploreAsPredicates(
 *         Set.of("big", "small", "important"),  // seed adjectives
 *         5.0,   // min logDice
 *         100,   // max nouns per iteration
 *         3      // max recursion depth
 *     );
 *
 *     System.out.println("All adjectives found: " + result.getAllAdjectives());
 *     System.out.println("All nouns found: " + result.getAllNouns());
 *     System.out.println("Adjective-noun edges: " + result.getEdges());
 * }
 * }</pre>
 *
 * <h2>Patterns explored:</h2>
 * <ul>
 *   <li>Adjective as predicate: "X be/remain/seem ADJ" (tag="NN.*" [word="be|remain|seem"] [tag="JJ.*"])</li>
 *   <li>Adjective modifying noun: "[tag='JJ.*']~{0,3}" (ADJ noun)</li>
 * </ul>
 */
public class SnowballCollocations implements AutoCloseable {

    private final WordSketchQueryExecutor executor;

    public SnowballCollocations(String indexPath) throws IOException {
        this.executor = new WordSketchQueryExecutor(indexPath);
    }

    /**
     * Explore collocations with adjectives as PREDICATES.
     * This explores patterns like:
     * <ul>
     *   <li>Linking verb pattern: "X be/remain/seem ADJ" (X is ADJ)</li>
     *   <li>Attributive pattern: "ADJ X" (ADJ modifying X)</li>
     * </ul>
     *
     * @param seedAdjectives Initial set of adjectives to start from
     * @param minLogDice Minimum logDice score for results
     * @param maxPerIteration Maximum results per iteration
     * @param maxDepth Maximum recursion depth
     * @return SnowballResult containing all discovered adjectives, nouns, and edges
     */
    public SnowballResult exploreAsPredicates(
            Set<String> seedAdjectives,
            double minLogDice,
            int maxPerIteration,
            int maxDepth) throws IOException {

        Set<String> allAdjectives = new LinkedHashSet<>(seedAdjectives);
        Set<String> allNouns = new LinkedHashSet<>();
        List<Edge> edges = new ArrayList<>();

        Set<String> currentAdjectives = new LinkedHashSet<>(seedAdjectives);

        System.out.println("\n=== SNOWBALL COLLOCATION EXPLORER ===");
        System.out.println("Seed adjectives: " + seedAdjectives);
        System.out.println("Min logDice: " + minLogDice + ", Max per iteration: " + maxPerIteration);
        System.out.println("-".repeat(60));

        for (int depth = 0; depth < maxDepth && !currentAdjectives.isEmpty(); depth++) {
            System.out.println("\nDepth " + depth + ": Processing " + currentAdjectives.size() + " adjectives");

            // Step 1: For each adjective, find nouns it modifies
            Map<String, Set<String>> adjToNouns = new LinkedHashMap<>();
            for (String adj : currentAdjectives) {
                // Pattern: [tag="JJ.*"]~{0,3} - find nouns near adjectives
                // The adjective is the headword, we find nouns as collocates
                String pattern = "[tag=\"NN.*\"]~{0,3}";
                List<WordSketchResult> results = executor.findCollocations(adj, pattern, minLogDice, maxPerIteration);

                Set<String> nouns = new LinkedHashSet<>();
                for (WordSketchResult r : results) {
                    nouns.add(r.getLemma());
                    edges.add(new Edge(adj, r.getLemma(), r.getLogDice(), "attributive"));
                }
                adjToNouns.put(adj, nouns);

                if (!nouns.isEmpty()) {
                    System.out.println("  " + adj + " -> " + nouns.size() + " nouns: " +
                        String.join(", ", new ArrayList<>(nouns).subList(0, Math.min(5, nouns.size()))) + "...");
                }
            }

            // Collect all nouns found
            Set<String> newNouns = new LinkedHashSet<>();
            for (Set<String> nouns : adjToNouns.values()) {
                newNouns.addAll(nouns);
            }
            allNouns.addAll(newNouns);
            System.out.println("  Total new nouns found: " + newNouns.size());

            if (newNouns.isEmpty()) {
                System.out.println("  No new nouns found, stopping.");
                break;
            }

            // Step 2: For each noun, find adjectives that modify it
            Set<String> newAdjectives = new LinkedHashSet<>();
            Map<String, Set<String>> nounToAdjs = new LinkedHashMap<>();

            for (String noun : newNouns) {
                // Pattern: [tag="JJ.*"]~{0,3} - find adjectives near nouns
                String pattern = "[tag=\"JJ.*\"]~{0,3}";
                List<WordSketchResult> results = executor.findCollocations(noun, pattern, minLogDice, maxPerIteration);

                Set<String> adjs = new LinkedHashSet<>();
                for (WordSketchResult r : results) {
                    if (!allAdjectives.contains(r.getLemma())) {
                        adjs.add(r.getLemma());
                    }
                    edges.add(new Edge(r.getLemma(), noun, r.getLogDice(), "attributive"));
                }
                nounToAdjs.put(noun, adjs);
                newAdjectives.addAll(adjs);

                if (!adjs.isEmpty()) {
                    System.out.println("  " + noun + " <- " + adjs.size() + " new adjectives: " +
                        String.join(", ", new ArrayList<>(adjs).subList(0, Math.min(5, adjs.size()))) + "...");
                }
            }

            // Remove seed adjectives from "new" to avoid infinite loops
            newAdjectives.removeAll(seedAdjectives);

            allAdjectives.addAll(newAdjectives);
            currentAdjectives = newAdjectives;

            System.out.println("  Total new adjectives found: " + newAdjectives.size());
        }

        System.out.println("-".repeat(60));
        System.out.println("RESULT: " + allAdjectives.size() + " adjectives, " + allNouns.size() + " nouns, " + edges.size() + " edges");

        return new SnowballResult(allAdjectives, allNouns, edges);
    }

    /**
     * Explore linking verb constructions (adjective predicates).
     * Pattern: "X be/remain/seem/appear/feel/get/become ADJ"
     *
     * @param seedNouns Initial set of nouns to start from
     * @param minLogDice Minimum logDice score
     * @param maxPerIteration Maximum results per iteration
     * @param maxDepth Maximum recursion depth
     * @return SnowballResult with discovered adjectives and their links
     */
    public SnowballResult exploreLinkingVerbPredicates(
            Set<String> seedNouns,
            double minLogDice,
            int maxPerIteration,
            int maxDepth) throws IOException {

        // Pattern for linking verbs: [word="be|remain|seem|appear|feel|get|become|look|smell|taste"] [tag="JJ.*"]
        final String linkingVerbPattern = "[word=\"be|remain|seem|appear|feel|get|become|look|smell|taste\"] [tag=\"JJ.*\"]";

        Set<String> allAdjectives = new LinkedHashSet<>();
        Set<String> allNouns = new LinkedHashSet<>(seedNouns);
        List<Edge> edges = new ArrayList<>();

        Set<String> currentNouns = new LinkedHashSet<>(seedNouns);

        System.out.println("\n=== SNOWBALL LINKING VERB EXPLORER ===");
        System.out.println("Seed nouns: " + seedNouns);
        System.out.println("Pattern: " + linkingVerbPattern);
        System.out.println("-".repeat(60));

        for (int depth = 0; depth < maxDepth && !currentNouns.isEmpty(); depth++) {
            System.out.println("\nDepth " + depth + ": Processing " + currentNouns.size() + " nouns");

            // For each noun, find adjectives linked via linking verbs
            Set<String> newAdjectives = new LinkedHashSet<>();

            // OPTIMIZATION: Instead of scanning all occurrences of each noun,
            // search for the linking verb + adjective pattern directly, then check for nouns
            System.out.println("  Searching for linking verb + adjective patterns...");
            Map<String, List<String>> nounToAdjectives = executor.findLinkingVerbPredicates(
                currentNouns, minLogDice, maxPerIteration);
            
            for (Map.Entry<String, List<String>> entry : nounToAdjectives.entrySet()) {
                String noun = entry.getKey();
                List<String> adjectives = entry.getValue();
                
                if (!adjectives.isEmpty()) {
                    System.out.println("  " + noun + " <- " + adjectives.size() + " adjectives: " +
                        adjectives.stream().limit(5).collect(Collectors.joining(", ")) + "...");
                    
                    for (String adj : adjectives) {
                        newAdjectives.add(adj);
                        edges.add(new Edge(noun, adj, minLogDice, "linking"));
                    }
                }
            }

            allAdjectives.addAll(newAdjectives);
            System.out.println("  Total new adjectives found: " + newAdjectives.size());

            if (newAdjectives.isEmpty()) {
                System.out.println("  No new adjectives found, stopping.");
                break;
            }

            // Now for each new adjective, find related nouns (attributive use)
            System.out.println("  Searching for nouns modified by adjectives...");
            Map<String, List<String>> adjectiveToNouns = executor.findAttributiveNouns(
                newAdjectives, minLogDice, maxPerIteration);
            
            Set<String> newNouns = new LinkedHashSet<>();
            for (Map.Entry<String, List<String>> entry : adjectiveToNouns.entrySet()) {
                String adj = entry.getKey();
                List<String> nouns = entry.getValue();
                
                if (!nouns.isEmpty()) {
                    System.out.println("    '" + adj + "': found " + nouns.size() + " nouns: " +
                        nouns.stream().limit(5).collect(Collectors.joining(", ")) + "...");
                    
                    for (String noun : nouns) {
                        if (!allNouns.contains(noun)) {
                            newNouns.add(noun);
                        }
                        edges.add(new Edge(adj, noun, minLogDice, "attributive"));
                    }
                }
            }

            allNouns.addAll(newNouns);
            currentNouns = newNouns;

            System.out.println("  Total new nouns found: " + newNouns.size());
        }

        System.out.println("-".repeat(60));
        System.out.println("RESULT: " + allAdjectives.size() + " adjectives, " + allNouns.size() + " nouns, " + edges.size() + " edges");

        return new SnowballResult(allAdjectives, allNouns, edges);
    }

    /**
     * Find all adjectives that can modify any of the given nouns.
     * Simplified single-step query.
     */
    public Map<String, List<WordSketchResult>> findAdjectivesForNouns(
            Set<String> nouns,
            double minLogDice,
            int maxResults) throws IOException {

        Map<String, List<WordSketchResult>> result = new LinkedHashMap<>();
        for (String noun : nouns) {
            String pattern = "[tag=\"JJ.*\"]~{0,3}";
            result.put(noun, executor.findCollocations(noun, pattern, minLogDice, maxResults));
        }
        return result;
    }

    /**
     * Find all nouns modified by any of the given adjectives.
     * Simplified single-step query.
     */
    public Map<String, List<WordSketchResult>> findNounsForAdjectives(
            Set<String> adjectives,
            double minLogDice,
            int maxResults) throws IOException {

        Map<String, List<WordSketchResult>> result = new LinkedHashMap<>();
        for (String adj : adjectives) {
            String pattern = "[tag=\"NN.*\"]~{0,3}";
            result.put(adj, executor.findCollocations(adj, pattern, minLogDice, maxResults));
        }
        return result;
    }

    @Override
    public void close() throws IOException {
        // WordSketchQueryExecutor doesn't implement AutoCloseable directly
        // but we keep the interface for potential future use
    }

    /**
     * Result of snowball exploration containing all discovered elements and their relationships.
     */
    public static class SnowballResult {
        private final Set<String> allAdjectives;
        private final Set<String> allNouns;
        private final List<Edge> edges;

        public SnowballResult(Set<String> allAdjectives, Set<String> allNouns, List<Edge> edges) {
            this.allAdjectives = allAdjectives;
            this.allNouns = allNouns;
            this.edges = edges;
        }

        public Set<String> getAllAdjectives() {
            return allAdjectives;
        }

        public Set<String> getAllNouns() {
            return allNouns;
        }

        public List<Edge> getEdges() {
            return edges;
        }

        public Map<String, Double> getAdjectiveScores() {
            Map<String, Double> scores = new LinkedHashMap<>();
            for (Edge e : edges) {
                if (e.source != null && e.source.matches(".*[JjJj].*")) {
                    // Source is adjective (heuristic)
                    scores.merge(e.target, e.weight, Double::sum);
                }
            }
            return scores;
        }

        public Map<String, Set<String>> getAdjectiveToNounsMap() {
            Map<String, Set<String>> map = new LinkedHashMap<>();
            for (Edge e : edges) {
                map.computeIfAbsent(e.source, k -> new LinkedHashSet<>()).add(e.target);
            }
            return map;
        }

        public Map<String, Set<String>> getNounToAdjectivesMap() {
            Map<String, Set<String>> map = new LinkedHashMap<>();
            for (Edge e : edges) {
                map.computeIfAbsent(e.target, k -> new LinkedHashSet<>()).add(e.source);
            }
            return map;
        }

        @Override
        public String toString() {
            return String.format("SnowballResult(%d adjectives, %d nouns, %d edges)",
                allAdjectives.size(), allNouns.size(), edges.size());
        }
    }

    /**
     * Edge in the adjective-noun graph.
     */
    public static class Edge {
        public final String source;  // adjective
        public final String target;  // noun
        public final double weight;  // logDice score
        public final String type;    // "attributive", "predicative", "linking"

        public Edge(String source, String target, double weight, String type) {
            this.source = source;
            this.target = target;
            this.weight = weight;
            this.type = type;
        }

        @Override
        public String toString() {
            return String.format("%s --(%s, %.2f)--> %s", source, type, weight, target);
        }
    }
}

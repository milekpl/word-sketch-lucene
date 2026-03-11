package pl.marcinmilkowski.word_sketch.model;


import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Immutable result of semantic field exploration from a single seed word.
 *
 * <p>Produced by {@code SemanticFieldExplorer#exploreByPattern}. All collections are
 * non-null; empty collections indicate the exploration found no candidates. Use
 * {@link #isEmpty()} to distinguish empty results from null.</p>
 */
public class ExplorationResult {
    private final String seed;
    private final Map<String, Double> seedCollocates;  // collocate -> logDice with seed
    private final Map<String, Long> seedCollocateFrequencies;  // collocate -> raw frequency
    private final List<DiscoveredNoun> discoveredNouns;
    private final List<CoreCollocate> coreCollocates;

    public ExplorationResult(String seed, Map<String, Double> seedCollocates,
            Map<String, Long> seedCollocateFrequencies,
            List<DiscoveredNoun> discoveredNouns, List<CoreCollocate> coreCollocates) {
        this.seed = seed;
        this.seedCollocates = seedCollocates;
        this.seedCollocateFrequencies = seedCollocateFrequencies;
        this.discoveredNouns = discoveredNouns;
        this.coreCollocates = coreCollocates;
    }

    /** @return the seed word this result was built from; never null */
    public String getSeed() { return seed; }

    /** @return collocate lemma → logDice score for the seed; never null */
    public Map<String, Double> getSeedCollocates() { return seedCollocates; }

    /** @return collocate lemma → raw corpus frequency for the seed; never null */
    public Map<String, Long> getSeedCollocateFrequencies() { return seedCollocateFrequencies; }

    /** @return discovered nouns sorted by relevance score descending; never null */
    public List<DiscoveredNoun> getDiscoveredNouns() { return discoveredNouns; }

    /** @return core collocates shared by most discovered nouns; never null */
    public List<CoreCollocate> getCoreCollocates() { return coreCollocates; }

    /**
     * Returns an empty result representing a seed word for which no exploration data was found.
     *
     * @param seed the seed word; must not be null
     * @return non-null empty result
     */
    public static ExplorationResult empty(String seed) {
        return new ExplorationResult(seed, Map.of(), Map.of(), List.of(), List.of());
    }

    /** @return {@code true} when no nouns were discovered; i.e. the result is empty */
    public boolean isEmpty() {
        return discoveredNouns.isEmpty();
    }

    /**
     * Returns the top {@code n} discovered nouns by relevance score.
     *
     * @param n maximum number of nouns to return; non-positive returns an empty list
     * @return sublist of discovered nouns, never null
     */
    public List<DiscoveredNoun> getTopNouns(int n) {
        return discoveredNouns.stream().limit(n).collect(Collectors.toList());
    }

    /**
     * Returns discovered nouns that share at least {@code minShared} collocates with the seed.
     *
     * @param minShared minimum shared-collocate count (inclusive); use 0 to return all
     * @return filtered list, never null
     */
    public List<DiscoveredNoun> getNounsWithMinShared(int minShared) {
        return discoveredNouns.stream()
            .filter(n -> n.sharedCount() >= minShared)
            .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return String.format("ExplorationResult(seed='%s', collocates=%d, discovered=%d, core=%d)",
            seed, seedCollocates.size(), discoveredNouns.size(), coreCollocates.size());
    }

}

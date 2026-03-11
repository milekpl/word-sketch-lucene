package pl.marcinmilkowski.word_sketch.model;


import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Immutable result of semantic field exploration from one or more seed words.
 *
 * <p>Produced by {@code SemanticFieldExplorer#exploreByPattern}. All collections are
 * non-null; empty collections indicate the exploration found no candidates. Use
 * {@link #isEmpty()} to distinguish empty results from null.</p>
 *
 * <p><strong>Why a class rather than a record:</strong> {@code ExplorationResult} exposes
 * computed accessors ({@link #isEmpty()}) and a factory method ({@link #empty(String)}) that
 * go beyond raw field access. Representing it as a record would force callers to synthesise
 * these derived views externally. A plain immutable class with a private constructor and
 * factory keeps the construction logic encapsulated and co-located with the type.</p>
 */
public class ExplorationResult {
    /**
     * The seed words this result was built from.
     *
     * <p>In single-seed mode this is a one-element list (e.g. {@code ["house"]});
     * in multi-seed mode it holds all seeds (e.g. {@code ["theory", "model", "hypothesis"]}).
     * Use {@link #seed()} for a combined display string and {@link #seeds()} to iterate
     * individual lemmas.</p>
     */
    private final List<String> seeds;
    private final Map<String, Double> seedCollocates;  // collocate -> logDice with seed
    private final Map<String, Long> seedCollocateFrequencies;  // collocate -> raw frequency
    private final List<DiscoveredNoun> discoveredNouns;
    private final List<CoreCollocate> coreCollocates;

    public ExplorationResult(List<String> seeds, Map<String, Double> seedCollocates,
            Map<String, Long> seedCollocateFrequencies,
            List<DiscoveredNoun> discoveredNouns, List<CoreCollocate> coreCollocates) {
        this.seeds = List.copyOf(seeds);
        this.seedCollocates = seedCollocates;
        this.seedCollocateFrequencies = seedCollocateFrequencies;
        this.discoveredNouns = discoveredNouns;
        this.coreCollocates = coreCollocates;
    }

    /**
     * Returns the seed word(s) as a combined display string.
     * In single-seed mode this is the lemma; in multi-seed mode it is comma-joined
     * (e.g. {@code "theory,model,hypothesis"}). Use {@link #seeds()} to iterate individual lemmas.
     */
    public String seed() { return String.join(",", seeds); }

    /**
     * Returns individual seed lemmas as an immutable list.
     * In single-seed mode returns a one-element list; in multi-seed mode returns all seeds.
     *
     * @return non-null, non-empty list of individual seed lemmas
     */
    public List<String> seeds() {
        return seeds;
    }

    /** @return collocate lemma → logDice score for the seed; never null, may be empty */
    public Map<String, Double> seedCollocates() { return seedCollocates; }

    /** @return collocate lemma → raw corpus frequency for the seed; never null, may be empty */
    public Map<String, Long> seedCollocateFrequencies() { return seedCollocateFrequencies; }

    /** @return discovered nouns sorted by relevance score descending; never null, may be empty */
    public List<DiscoveredNoun> discoveredNouns() { return discoveredNouns; }

    /** @return core collocates shared by most discovered nouns; never null, may be empty */
    public List<CoreCollocate> coreCollocates() { return coreCollocates; }

    /**
     * Returns an empty result representing a seed word for which no exploration data was found.
     *
     * @param seed the seed word; must not be null
     * @return non-null empty result
     */
    public static ExplorationResult empty(String seed) {
        Objects.requireNonNull(seed, "seed must not be null");
        return new ExplorationResult(List.of(seed), Map.of(), Map.of(), List.of(), List.of());
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
    public List<DiscoveredNoun> topNouns(int n) {
        return discoveredNouns.stream().limit(n).collect(Collectors.toList());
    }

    /**
     * Returns discovered nouns that share at least {@code minShared} collocates with the seed.
     *
     * @param minShared minimum shared-collocate count (inclusive); use 0 to return all
     * @return filtered list, never null
     */
    public List<DiscoveredNoun> nounsWithMinShared(int minShared) {
        return discoveredNouns.stream()
            .filter(n -> n.sharedCount() >= minShared)
            .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return String.format("ExplorationResult(seed='%s', collocates=%d, discovered=%d, core=%d)",
            seed(), seedCollocates.size(), discoveredNouns.size(), coreCollocates.size());
    }

}

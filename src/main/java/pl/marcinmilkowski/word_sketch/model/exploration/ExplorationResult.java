package pl.marcinmilkowski.word_sketch.model.exploration;


import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * Immutable result of semantic field exploration from one or more seed words.
 *
 * <p>Produced by {@code SemanticFieldExplorer#exploreByRelation}. All collections are
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
    private final @NonNull List<String> seeds;
    private final @NonNull Map<String, Double> seedCollocates;  // collocate -> logDice with seed
    private final @NonNull Map<String, Long> seedCollocateFrequencies;  // collocate -> raw frequency
    private final @NonNull List<DiscoveredNoun> discoveredNouns;
    private final @NonNull List<CoreCollocate> coreCollocates;
    /** seed lemma → (collocate lemma → logDice); empty map means not available. */
    private final @NonNull Map<String, Map<String, Double>> perSeedCollocates;

    private ExplorationResult(List<String> seeds, Map<String, Double> seedCollocates,
            Map<String, Long> seedCollocateFrequencies,
            List<DiscoveredNoun> discoveredNouns, List<CoreCollocate> coreCollocates,
            Map<String, Map<String, Double>> perSeedCollocates) {
        this.seeds = List.copyOf(seeds);
        this.seedCollocates = Map.copyOf(seedCollocates);
        this.seedCollocateFrequencies = Map.copyOf(seedCollocateFrequencies);
        this.discoveredNouns = List.copyOf(discoveredNouns);
        this.coreCollocates = List.copyOf(coreCollocates);
        this.perSeedCollocates = Map.copyOf(perSeedCollocates);
    }

    /**
     * Creates an {@code ExplorationResult} from fully-constructed field values.
     * This is the sole public construction path outside of {@link #empty(String)}.
     *
     * @param seeds                  the seed word(s); single-element list in single-seed mode,
     *                               multi-element in multi-seed mode
     * @param seedCollocates         collocate lemma → logDice score for the seed(s); must not be null
     * @param seedCollocateFrequencies collocate lemma → raw corpus frequency; must not be null
     * @param discoveredNouns        nouns discovered via reverse collocate expansion, sorted by
     *                               relevance score descending; must not be null
     * @param coreCollocates         collocates shared by most discovered nouns; must not be null
     * @param perSeedCollocates      seed lemma → (collocate lemma → logDice) for per-seed edge
     *                               attribution; single entry in single-seed mode; must not be null
     */
    public static ExplorationResult of(List<String> seeds, Map<String, Double> seedCollocates,
            Map<String, Long> seedCollocateFrequencies,
            List<DiscoveredNoun> discoveredNouns, List<CoreCollocate> coreCollocates,
            Map<String, Map<String, Double>> perSeedCollocates) {
        Objects.requireNonNull(seeds, "seeds must not be null");
        Objects.requireNonNull(seedCollocates, "seedCollocates must not be null");
        Objects.requireNonNull(seedCollocateFrequencies, "seedCollocateFrequencies must not be null");
        Objects.requireNonNull(discoveredNouns, "discoveredNouns must not be null");
        Objects.requireNonNull(coreCollocates, "coreCollocates must not be null");
        Objects.requireNonNull(perSeedCollocates, "perSeedCollocates must not be null");
        return new ExplorationResult(seeds, seedCollocates, seedCollocateFrequencies,
                discoveredNouns, coreCollocates, perSeedCollocates);
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
     * Returns per-seed collocate maps for accurate edge attribution.
     * In single-seed mode contains one entry; in multi-seed mode one entry per seed.
     * @return seed lemma → (collocate lemma → logDice); never null, may be empty
     */
    public Map<String, Map<String, Double>> perSeedCollocates() { return perSeedCollocates; }

    /**
     * Returns an empty result representing a seed word for which no exploration data was found.
     *
     * @param seed the seed word; must not be null
     * @return non-null empty result
     */
    public static ExplorationResult empty(String seed) {
        Objects.requireNonNull(seed, "seed must not be null");
        return new ExplorationResult(List.of(seed), Map.of(), Map.of(), List.of(), List.of(), Map.of());
    }

    /** @return {@code true} when no nouns were discovered; i.e. the result is empty */
    public boolean isEmpty() {
        return discoveredNouns.isEmpty();
    }

    @Override
    public String toString() {
        return String.format("ExplorationResult(seed='%s', collocates=%d, discovered=%d, core=%d)",
            seed(), seedCollocates.size(), discoveredNouns.size(), coreCollocates.size());
    }

}

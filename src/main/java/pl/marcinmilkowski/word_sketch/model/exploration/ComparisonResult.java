package pl.marcinmilkowski.word_sketch.model.exploration;


import java.util.List;
import java.util.stream.Collectors;

/**
 * Complete comparison result with graded adjective profiles for a set of seed nouns.
 *
 * <p>Produced by {@link pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer#compareCollocateProfiles}.
 * All collections are non-null; use {@code getNouns().isEmpty()} to detect an empty (no-data) result.
 * Obtain an empty sentinel via {@link #empty()}.</p>
 */
public class ComparisonResult {
    private final List<String> nouns;
    private final List<CollocateProfile> collocates;

    private ComparisonResult(List<String> nouns, List<CollocateProfile> collocates) {
        this.nouns = List.copyOf(nouns);
        this.collocates = collocates;
    }

    /**
     * Factory method for constructing a {@code ComparisonResult}, following the same
     * convention as {@link pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult}.
     *
     * @param nouns      the seed nouns the comparison was built from; must not be null
     * @param collocates all collocate profiles; must not be null
     * @return a new ComparisonResult containing the given data
     */
    public static ComparisonResult of(List<String> nouns, List<CollocateProfile> collocates) {
        return new ComparisonResult(nouns, collocates);
    }

    public static ComparisonResult empty() {
        return new ComparisonResult(List.of(), List.of());
    }

    /** @return the seed nouns this comparison was built from; never null, may be empty */
    public List<String> nouns() { return nouns; }

    /** @return all collocate profiles regardless of sharing category; never null, may be empty */
    public List<CollocateProfile> collocates() { return collocates; }

    /**
     * Single-pass counts of all three sharing categories to avoid 3 separate stream iterations.
     */
    public record SummaryCounts(int fullyShared, int partiallyShared, int specific) {}

    /**
     * Returns counts for fully-shared, partially-shared, and specific collocates in a single pass.
     */
    public SummaryCounts summaryCounts() {
        int fullyShared = 0, partiallyShared = 0, specific = 0;
        for (CollocateProfile c : collocates) {
            switch (c.sharingCategory()) {
                case FULLY_SHARED    -> fullyShared++;
                case PARTIALLY_SHARED -> partiallyShared++;
                case SPECIFIC        -> specific++;
            }
        }
        return new SummaryCounts(fullyShared, partiallyShared, specific);
    }

    /**
     * Returns collocates that occur only in the collocate profile of {@code noun},
     * sorted by descending logDice score.
     *
     * @param noun one of the seed nouns from {@link #nouns()}; returns an empty list for unknown nouns
     */
    public List<CollocateProfile> specificTo(String noun) {
        return collocates.stream()
            .filter(a -> a.isSpecific() && a.nounScores().getOrDefault(noun, 0.0) > 0)
            .sorted((x, y) -> Double.compare(y.maxLogDice(), x.maxLogDice()))
            .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        SummaryCounts counts = summaryCounts();
        return String.format("ComparisonResult(%d nouns, %d collocates: %d shared, %d specific)",
            nouns.size(), collocates.size(), counts.fullyShared() + counts.partiallyShared(), counts.specific());
    }

}

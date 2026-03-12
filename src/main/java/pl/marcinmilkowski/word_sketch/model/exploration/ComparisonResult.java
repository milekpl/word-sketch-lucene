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
    private final List<CollocateProfile> adjectives;

    public ComparisonResult(List<String> nouns, List<CollocateProfile> adjectives) {
        this.nouns = nouns;
        this.adjectives = adjectives;
    }

    public static ComparisonResult empty() {
        return new ComparisonResult(List.of(), List.of());
    }

    /** @return the seed nouns this comparison was built from; never null, may be empty */
    public List<String> nouns() { return nouns; }

    /** @return all collocate profiles regardless of sharing category; never null, may be empty */
    public List<CollocateProfile> collocates() { return adjectives; }

    /**
     * Single-pass counts of all three sharing categories to avoid 3 separate stream iterations.
     */
    public record SummaryCounts(int fullyShared, int partiallyShared, int specific) {}

    /**
     * Returns counts for fully-shared, partially-shared, and specific adjectives in a single pass.
     */
    public SummaryCounts summaryCounts() {
        int fullyShared = 0, partiallyShared = 0, specific = 0;
        for (CollocateProfile a : adjectives) {
            switch (a.sharingCategory()) {
                case FULLY_SHARED    -> fullyShared++;
                case PARTIALLY_SHARED -> partiallyShared++;
                case SPECIFIC        -> specific++;
            }
        }
        return new SummaryCounts(fullyShared, partiallyShared, specific);
    }

    /** @return collocates present in every seed noun's collocate profile */
    public List<CollocateProfile> fullyShared() {
        return adjectives.stream()
            .filter(a -> a.presentInCount() == nouns.size())
            .collect(Collectors.toList());
    }

    /** @return collocates shared by at least 2 nouns but not all */
    public List<CollocateProfile> partiallyShared() {
        return adjectives.stream()
            .filter(a -> a.presentInCount() >= 2 && a.presentInCount() < nouns.size())
            .collect(Collectors.toList());
    }

    /** @return collocates that occur in the collocate profile of exactly one seed noun */
    public List<CollocateProfile> specific() {
        return adjectives.stream()
            .filter(a -> a.presentInCount() == 1)
            .collect(Collectors.toList());
    }

    /**
     * Returns adjectives that occur only in the collocate profile of {@code noun},
     * sorted by descending logDice score.
     *
     * @param noun one of the seed nouns from {@link #nouns()}; returns an empty list for unknown nouns
     */
    public List<CollocateProfile> specificTo(String noun) {
        return adjectives.stream()
            .filter(a -> a.presentInCount() == 1 && a.nounScores().getOrDefault(noun, 0.0) > 0)
            .sorted((x, y) -> Double.compare(y.maxLogDice(), x.maxLogDice()))
            .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        SummaryCounts counts = summaryCounts();
        return String.format("ComparisonResult(%d nouns, %d adjectives: %d shared, %d specific)",
            nouns.size(), adjectives.size(), counts.fullyShared() + counts.partiallyShared(), counts.specific());
    }

}

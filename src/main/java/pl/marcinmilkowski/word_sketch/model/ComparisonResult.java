package pl.marcinmilkowski.word_sketch.model;


import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Complete comparison result with graded adjective profiles for a set of seed nouns.
 *
 * <p>Produced by {@code CollocateProfileComparator#compareCollocateProfiles}. All collections
 * are non-null; use {@code getNouns().isEmpty()} to detect an empty (no-data) result.
 * Obtain an empty sentinel via {@link #empty()}.</p>
 */
public class ComparisonResult {
    private final List<String> nouns;
    private final List<AdjectiveProfile> adjectives;

    public ComparisonResult(List<String> nouns, List<AdjectiveProfile> adjectives) {
        this.nouns = nouns;
        this.adjectives = adjectives;
    }

    public static ComparisonResult empty() {
        return new ComparisonResult(List.of(), List.of());
    }

    /** @return the seed nouns this comparison was built from; never null */
    public List<String> getNouns() { return nouns; }

    /** @return all adjective profiles regardless of sharing category; never null */
    public List<AdjectiveProfile> getAllAdjectives() { return adjectives; }

    /** @return adjectives present in every seed noun's collocate profile */
    public List<AdjectiveProfile> getFullyShared() {
        return adjectives.stream()
            .filter(a -> a.presentInCount() == nouns.size())
            .collect(Collectors.toList());
    }

    /** @return adjectives shared by at least 2 nouns but not all */
    public List<AdjectiveProfile> getPartiallyShared() {
        return adjectives.stream()
            .filter(a -> a.presentInCount() >= 2 && a.presentInCount() < nouns.size())
            .collect(Collectors.toList());
    }

    /** @return adjectives that occur in the collocate profile of exactly one seed noun */
    public List<AdjectiveProfile> getSpecific() {
        return adjectives.stream()
            .filter(a -> a.presentInCount() == 1)
            .collect(Collectors.toList());
    }

    /**
     * Returns adjectives that occur only in the collocate profile of {@code noun},
     * sorted by descending logDice score.
     *
     * @param noun one of the seed nouns from {@link #getNouns()}; returns an empty list for unknown nouns
     */
    public List<AdjectiveProfile> getSpecificTo(String noun) {
        return adjectives.stream()
            .filter(a -> a.presentInCount() == 1 && a.nounScores().getOrDefault(noun, 0.0) > 0)
            .sorted((x, y) -> Double.compare(y.maxLogDice(), x.maxLogDice()))
            .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        int shared = (int) adjectives.stream().filter(a -> a.presentInCount() >= 2).count();
        int specific = (int) adjectives.stream().filter(a -> a.presentInCount() == 1).count();
        return String.format("ComparisonResult(%d nouns, %d adjectives: %d shared, %d specific)",
            nouns.size(), adjectives.size(), shared, specific);
    }

}

package pl.marcinmilkowski.word_sketch.indexer.hybrid;

import java.util.List;

/**
 * Precomputed collocations for a single headword.
 * 
 * Contains the top-K collocates ranked by logDice association score.
 * Built offline during indexing and stored in collocations.bin for
 * O(1) retrieval at query time.
 */
public record CollocationEntry(
    String headword,           // The headword lemma (lowercase)
    long headwordFrequency,    // Total corpus frequency of headword
    List<Collocation> collocates  // Top-K collocates, sorted by logDice desc
) {

    /**
     * Get the number of collocates.
     */
    public int size() {
        return collocates.size();
    }

    /**
     * Check if entry is empty.
     */
    public boolean isEmpty() {
        return collocates.isEmpty();
    }

    /**
     * Get top N collocates.
     */
    public List<Collocation> topN(int n) {
        return collocates.stream()
            .limit(n)
            .toList();
    }

    /**
     * Filter by minimum logDice threshold.
     */
    public List<Collocation> filterByLogDice(double minLogDice) {
        return collocates.stream()
            .filter(c -> c.logDice() >= minLogDice)
            .toList();
    }

    @Override
    public String toString() {
        return String.format("CollocationEntry[%s freq=%d, %d collocates]",
            headword, headwordFrequency, collocates.size());
    }
}

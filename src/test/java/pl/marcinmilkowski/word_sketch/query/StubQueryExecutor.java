package pl.marcinmilkowski.word_sketch.query;

import pl.marcinmilkowski.word_sketch.model.sketch.*;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Shared stub implementation of {@link QueryExecutor} for use in tests.
 *
 * <p>All methods return empty collections or zero by default. Individual tests can
 * override specific methods using anonymous subclasses to provide custom behaviour.</p>
 *
 * <p>Example usage in a test:
 * <pre>{@code
 * QueryExecutor stub = new StubQueryExecutor() {
 *     @Override
 *     public List<CollocateResult> executeBcqlQuery(String p, int m) {
 *         return List.of(new CollocateResult("a sentence", null, 0, 5, "d1", "word", 1, 7.0));
 *     }
 * };
 * }</pre>
 * </p>
 */
public class StubQueryExecutor implements QueryExecutor {

    private static final java.util.regex.Pattern LEMMA_PATTERN =
            java.util.regex.Pattern.compile("lemma=[\"']([^\"']+)[\"']",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Extracts the first lemma value from a CQL/BCQL pattern string.
     * Returns an empty string when no lemma attribute is present.
     */
    public static String extractLemmaFromPattern(String pattern) {
        java.util.regex.Matcher m = LEMMA_PATTERN.matcher(pattern);
        return m.find() ? m.group(1) : "";
    }

    @Override
    public List<WordSketchResult> executeCollocations(
            String lemma, String cqlPattern, double minLogDice, int maxResults) throws IOException {
        return Collections.emptyList();
    }

    @Override
    public List<ConcordanceResult> executeCqlQuery(
            String cqlPattern, int maxResults) throws IOException {
        return Collections.emptyList();
    }

    @Override
    public List<CollocateResult> executeBcqlQuery(
            String bcqlPattern, int maxResults) throws IOException {
        return Collections.emptyList();
    }

    @Override
    public long getTotalFrequency(String lemma) throws IOException {
        return 0L;
    }

    @Override
    public List<WordSketchResult> executeSurfaceCollocations(
            String bcqlPattern, double minLogDice, int maxResults) throws IOException {
        return Collections.emptyList();
    }

    @Override
    public List<WordSketchResult> executeDependencyPattern(
            String lemma, String deprel, double minLogDice, int maxResults,
            String headPosConstraint) throws IOException {
        return Collections.emptyList();
    }

    @Override
    public void close() {}

    /**
     * Factory: creates a StubQueryExecutor that looks up collocate results from a map.
     * Both {@link #executeCollocations} and {@link #executeSurfaceCollocations} delegate to the
     * map using the lemma as key (lowercased).
     */
    public static StubQueryExecutor withCollocations(
            java.util.Map<String, List<WordSketchResult>> collocations) {
        return new StubQueryExecutor() {
            @Override
            public List<WordSketchResult> executeCollocations(
                    String lemma, String cqlPattern, double minLogDice, int maxResults) {
                return collocations.getOrDefault(lemma.toLowerCase(), Collections.emptyList());
            }

            @Override
            public List<WordSketchResult> executeSurfaceCollocations(
                    String bcqlPattern, double minLogDice, int maxResults) {
                String lemma = extractLemmaFromPattern(bcqlPattern);
                return collocations.getOrDefault(lemma.toLowerCase(), Collections.emptyList());
            }
        };
    }
}

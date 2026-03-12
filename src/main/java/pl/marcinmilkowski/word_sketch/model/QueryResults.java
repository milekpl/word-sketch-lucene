package pl.marcinmilkowski.word_sketch.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Common result classes for word sketch queries.
 * Used by BlackLabQueryExecutor and SemanticFieldExplorer.
 */
public class QueryResults {

    private QueryResults() {}

    /**
     * Result of a word sketch query containing collocation information.
     */
    public record WordSketchResult(String lemma, String pos, long frequency,
                                   double logDice, double relativeFrequency,
                                   List<String> examples) {
        /** Sentinel for missing POS information returned by the tagger. */
        public static final String UNKNOWN_POS = "unknown";
    }

    /**
     * Common interface for all concordance result types.
     * Use pattern-matching ({@code instanceof}) or the concrete subtype to access type-specific fields.
     */
    public sealed interface ConcordanceResult
            permits QueryResults.SnippetResult, QueryResults.CollocateResult {
        String sentence();
        int startOffset();
        int endOffset();
        String docId();
    }

    /** Plain concordance result carrying only sentence text and match position. */
    public record SnippetResult(String sentence, int startOffset, int endOffset, String docId)
            implements ConcordanceResult {}

    /** Scored collocate result produced by the BCQL scoring pipeline. */
    public record CollocateResult(String sentence, String rawXml,
                                   int startOffset, int endOffset, String docId,
                                   @Nullable String collocateLemma, long frequency, double logDice)
            implements ConcordanceResult {}
}

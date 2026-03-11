package pl.marcinmilkowski.word_sketch.query;

import java.util.List;

/**
 * Common result classes for word sketch queries.
 * Used by BlackLabQueryExecutor and SemanticFieldExplorer.
 */
public class QueryResults {

    private QueryResults() {
        // Utility class - prevent instantiation
    }

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
            permits QueryResults.SnippetResult, QueryResults.KwicResult, QueryResults.CollocateResult {
        String getSentence();
        int getStartOffset();
        int getEndOffset();
        String getDocId();
    }

    /** Plain concordance result carrying only sentence text and match position. */
    public record SnippetResult(String sentence, int startOffset, int endOffset, String docId)
            implements ConcordanceResult {
        @Override public String getSentence() { return sentence; }
        @Override public int getStartOffset() { return startOffset; }
        @Override public int getEndOffset() { return endOffset; }
        @Override public String getDocId() { return docId; }
    }

    /**
     * KWIC (keyword-in-context) concordance result with separate left/right context strings.
     * The full sentence is assembled as {@code leftContext + word + rightContext}.
     */
    public record KwicResult(String word, String leftContext, String rightContext,
                             String sentence, int startOffset, int endOffset, String docId)
            implements ConcordanceResult {
        /** Convenience constructor that assembles the sentence from the three context parts. */
        public KwicResult(String word, String leftContext, String rightContext,
                          int startOffset, int endOffset, String docId) {
            this(word, leftContext, rightContext,
                 leftContext + word + rightContext, startOffset, endOffset, docId);
        }
        @Override public String getSentence() { return sentence; }
        @Override public int getStartOffset() { return startOffset; }
        @Override public int getEndOffset() { return endOffset; }
        @Override public String getDocId() { return docId; }
    }

    /** Scored collocate result produced by the BCQL scoring pipeline. */
    public record CollocateResult(String sentence, String rawXml,
                                   int startOffset, int endOffset, String docId,
                                   String collocateLemma, long frequency, double logDice)
            implements ConcordanceResult {
        @Override public String getSentence() { return sentence; }
        @Override public int getStartOffset() { return startOffset; }
        @Override public int getEndOffset() { return endOffset; }
        @Override public String getDocId() { return docId; }
    }
}

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
    public static class WordSketchResult {
        private final String lemma;
        private final String pos;
        private final long frequency;
        private final double logDice;
        private final double relativeFrequency;
        private final List<String> examples;

        public WordSketchResult(String lemma, String pos, long frequency,
                               double logDice, double relativeFrequency,
                               List<String> examples) {
            this.lemma = lemma;
            this.pos = pos;
            this.frequency = frequency;
            this.logDice = logDice;
            this.relativeFrequency = relativeFrequency;
            this.examples = examples;
        }

        public String getLemma() { return lemma; }
        public String getPos() { return pos; }
        public long getFrequency() { return frequency; }
        public double getLogDice() { return logDice; }
        public double getRelativeFrequency() { return relativeFrequency; }
        public List<String> getExamples() { return examples; }
    }

    /**
     * Result of a concordance query containing sentence context.
     *
     * <p>All fields except {@code sentence}, {@code startOffset}, and {@code endOffset} may be
     * {@code null} / zero depending on how the result was produced:
     * <ul>
     *   <li>{@code rawXml} – present only when the BCQL executor captures forward-index XML.
     *   <li>{@code lemma}, {@code tag}, {@code word} – present only for token-level results;
     *       {@code null} for grouped collocate results.
     *   <li>{@code docId} – may be {@code null} for results produced without a document identifier.
     *   <li>{@code collocateLemma} – present only for grouped collocate results; {@code null}
     *       for plain concordance results.
     *   <li>{@code frequency}, {@code logDice} – meaningful only when {@code collocateLemma}
     *       is non-{@code null}; otherwise 0 / 0.0.
     * </ul>
     *
     * <p>Use the {@link #forSnippet} factory when only position and sentence text are available.
     */
    public static class ConcordanceResult {
        private final String sentence;
        private final String rawXml;
        private final String lemma;
        private final String tag;
        private final String word;
        private final int startOffset;
        private final int endOffset;
        private final String docId;
        private final String collocateLemma;
        private final long frequency;
        private final double logDice;

        /**
         * Primary constructor. All nullable fields are documented on the class.
         *
         * @param sentence       plain-text sentence (non-null)
         * @param rawXml         raw forward-index XML snippet, or {@code null}
         * @param lemma          lemma of the matched token, or {@code null}
         * @param tag            POS tag of the matched token, or {@code null}
         * @param word           surface form of the matched token, or {@code null}
         * @param startOffset    match start position in the document
         * @param endOffset      match end position in the document
         * @param docId          BlackLab document identifier, or {@code null}
         * @param collocateLemma collocate lemma for grouped results, or {@code null}
         * @param frequency      co-occurrence frequency (0 when no collocate)
         * @param logDice        logDice association score (0.0 when no collocate)
         */
        public ConcordanceResult(String sentence, String rawXml,
                                 String lemma, String tag, String word,
                                 int startOffset, int endOffset, String docId,
                                 String collocateLemma, long frequency, double logDice) {
            this.sentence = sentence;
            this.rawXml = rawXml;
            this.lemma = lemma;
            this.tag = tag;
            this.word = word;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.docId = docId;
            this.collocateLemma = collocateLemma;
            this.frequency = frequency;
            this.logDice = logDice;
        }

        /**
         * Factory for plain concordance results that carry only position and sentence text.
         * {@code rawXml}, {@code lemma}, {@code tag}, {@code word}, and {@code collocateLemma}
         * will all be {@code null}; {@code frequency} and {@code logDice} will be 0 / 0.0.
         */
        public static ConcordanceResult forSnippet(String sentence, int start, int end, String docId) {
            return new ConcordanceResult(sentence, null, null, null, null,
                                         start, end, docId, null, 0L, 0.0);
        }

        public String getSentence() { return sentence; }
        public String getRawXml() { return rawXml; }
        public String getLemma() { return lemma; }
        public String getTag() { return tag; }
        public String getWord() { return word; }
        public int getStartOffset() { return startOffset; }
        public int getEndOffset() { return endOffset; }
        public String getDocId() { return docId; }
        public String getCollocateLemma() { return collocateLemma; }
        public long getFrequency() { return frequency; }
        public double getLogDice() { return logDice; }
    }
}

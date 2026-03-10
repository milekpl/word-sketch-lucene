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
     */
    public static class ConcordanceResult {
        private final String sentence;
        private final String rawXml;           // Raw XML for toggle display
        private final String lemma;
        private final String tag;
        private final String word;
        private final int startOffset;
        private final int endOffset;
        private final String docId;
        private final String collocateLemma;  // For grouped collocate results
        private final long frequency;          // For grouped collocate results
        private final double logDice;         // For grouped collocate results

        public ConcordanceResult(String sentence, String lemma, String tag,
                                String word, int startOffset, int endOffset, String docId) {
            this.sentence = sentence;
            this.rawXml = null;
            this.lemma = lemma;
            this.tag = tag;
            this.word = word;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.docId = docId;
            this.collocateLemma = null;
            this.frequency = 0;
            this.logDice = 0.0;
        }

        /**
         * Constructor for grouped results with collocate info.
         */
        public ConcordanceResult(String snippet, int start, int end, String docId,
                                String collocateLemma, long frequency, double logDice) {
            this.sentence = snippet;
            this.rawXml = null;
            this.lemma = null;
            this.tag = null;
            this.word = null;
            this.startOffset = start;
            this.endOffset = end;
            this.docId = docId;
            this.collocateLemma = collocateLemma;
            this.frequency = frequency;
            this.logDice = logDice;
        }

        /**
         * Full constructor with raw XML for toggle display.
         */
        public ConcordanceResult(String sentence, String rawXml, int start, int end, String docId,
                                String collocateLemma, long frequency, double logDice) {
            this.sentence = sentence;
            this.rawXml = rawXml;
            this.lemma = null;
            this.tag = null;
            this.word = null;
            this.startOffset = start;
            this.endOffset = end;
            this.docId = docId;
            this.collocateLemma = collocateLemma;
            this.frequency = frequency;
            this.logDice = logDice;
        }

        /**
         * Simplified constructor without docId for backward compatibility.
         */
        public ConcordanceResult(String sentence, String lemma, String tag,
                                String word, int startOffset, int endOffset) {
            this(sentence, lemma, tag, word, startOffset, endOffset, null);
        }

        /**
         * Constructor for snippet-based results (sentence + offsets only).
         */
        public ConcordanceResult(String snippet, int start, int end, String docId) {
            this(snippet, null, null, null, start, end, docId);
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

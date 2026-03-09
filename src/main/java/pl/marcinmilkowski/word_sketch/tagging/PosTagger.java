package pl.marcinmilkowski.word_sketch.tagging;

import java.io.IOException;
import java.util.List;

/**
 * Interface for POS taggers that convert raw text to tagged tokens.
 */
public interface PosTagger {

    /**
     * Tag a single sentence.
     *
     * @param sentence The input sentence
     * @return List of tagged tokens
     */
    List<TaggedToken> tagSentence(String sentence) throws IOException;

    /**
     * Tag multiple sentences.
     *
     * @param sentences List of input sentences
     * @return List of token lists for each sentence
     */
    List<List<TaggedToken>> tagSentences(List<String> sentences) throws IOException;

    /**
     * Get the name of this tagger.
     */
    String getName();

    /**
     * Get the tagset used by this tagger.
     */
    String getTagset();

    /**
     * A single token with its tag and lemma information.
     */
    class TaggedToken {
        private final String word;
        private final String lemma;
        private final String tag;
        private final int position;

        public TaggedToken(String word, String lemma, String tag, int position) {
            this.word = word;
            this.lemma = lemma;
            this.tag = tag;
            this.position = position;
        }

        public String getWord() { return word; }
        public String getLemma() { return lemma; }
        public String getTag() { return tag; }
        public int getPosition() { return position; }

        /**
         * Get the broad POS group (noun, verb, adj, adv, etc.).
         */
        public String getPosGroup() {
            if (tag == null) return "other";
            char firstChar = tag.charAt(0);
            switch (firstChar) {
                case 'N': return "noun";
                case 'V': return "verb";
                case 'J': return "adj";
                case 'R': return "adv";
                case 'D': return "det";
                case 'P': return "pron";
                case 'I': return "prep";
                case 'C': return "conj";
                case 'U': return "punct";
                default: return "other";
            }
        }

        @Override
        public String toString() {
            return word + "\t" + tag + "\t" + lemma;
        }
    }
}

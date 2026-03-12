package pl.marcinmilkowski.word_sketch.query;

import org.jspecify.annotations.Nullable;

/**
 * Utility methods for analysing BCQL (BlackLab CQL) pattern strings.
 * These belong here rather than in {@link BlackLabSnippetParser} because they operate on
 * the <em>pattern text</em> itself, not on parsed XML snippet output.
 */
public final class BcqlPatternUtils {

    private static final java.util.regex.Pattern LEMMA_ATTR_RELAXED =
            java.util.regex.Pattern.compile("lemma=[\"']([^\"']+)[\"']",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private BcqlPatternUtils() {}

    /**
     * Extract the headword lemma from a BCQL pattern string.
     * Finds the first {@code lemma="..."} or {@code lemma='...'} attribute and returns its value.
     *
     * @param bcqlPattern the BCQL pattern string
     * @return the lemma value, or {@code null} if no lemma attribute is present
     */
    @Nullable
    public static String extractHeadword(String bcqlPattern) {
        java.util.regex.Matcher m = LEMMA_ATTR_RELAXED.matcher(bcqlPattern);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Find the position of a labeled capture group (e.g., "2:") in a BCQL pattern.
     * Returns the 1-based position of that token in the pattern.
     *
     * <p>Example: {@code "1:[xpos="NN.*"] [lemma="be|..."] 2:[xpos="JJ.*"]"}
     * <ul>
     *   <li>"1:" is at position 1</li>
     *   <li>"[lemma=...]" (unlabeled) is at position 2</li>
     *   <li>"2:" is at position 3</li>
     * </ul>
     *
     * @param pattern the BCQL pattern string
     * @param label   the numeric label to find (e.g., 2 for "2:")
     * @return the 1-based token position, or -1 if the label is not found
     */
    public static int findLabelTokenIndex(String pattern, int label) {
        if (pattern == null) {
            return -1;
        }
        String labelStr = label + ":";
        int labelIndex = pattern.indexOf(labelStr);
        if (labelIndex < 0) {
            return -1;
        }
        if (labelIndex + labelStr.length() < pattern.length() &&
            pattern.charAt(labelIndex + labelStr.length()) == '[') {
            int tokenPos = 0;
            for (int i = 0; i < pattern.length(); i++) {
                if (pattern.charAt(i) == '[') {
                    tokenPos++;
                    if (i == labelIndex + labelStr.length()) {
                        return tokenPos;
                    }
                }
            }
        }
        // -1 covers two cases: label absent (caught above) OR label present but not
        // immediately followed by '[' (e.g. "2:foo" — not a labelled token bracket)
        return -1;
    }
}

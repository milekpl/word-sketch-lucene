package pl.marcinmilkowski.word_sketch.query;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods for parsing BlackLab XML snippets and extracting collocates.
 */
class BlackLabSnippetParser {
    private static final Logger logger = LoggerFactory.getLogger(BlackLabSnippetParser.class);

    private static final java.util.regex.Pattern LEMMA_ATTR      = java.util.regex.Pattern.compile("lemma=\"([^\"]+)\"");
    private static final java.util.regex.Pattern XPOS_ATTR       = java.util.regex.Pattern.compile("xpos=\"([^\"]+)\"");
    private static final java.util.regex.Pattern UPOS_ATTR       = java.util.regex.Pattern.compile("upos=\"([^\"]+)\"");
    private static final java.util.regex.Pattern SENTENCE_BOUND_LEFT  = java.util.regex.Pattern.compile("[.!?]\\s+(?=[A-Z]|$)");
    private static final java.util.regex.Pattern SENTENCE_BOUND_RIGHT = java.util.regex.Pattern.compile("[.!?](?=\\s+[A-Z]|\\s*$)");
    private static final java.util.regex.Pattern XML_SENTENCE_OPEN   = java.util.regex.Pattern.compile("<s(?:\\s[^>]*)?>", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern XML_SENTENCE_CLOSE  = java.util.regex.Pattern.compile("</s>", java.util.regex.Pattern.CASE_INSENSITIVE);

    private BlackLabSnippetParser() {}

    /**
     * Extract lemma from matched text (XML format).
     * Finds the last {@code lemma="xxx"} attribute and returns it lowercased.
     * Used for grouping identity text from HitPropertyHitText results.
     *
     * @return the lowercased lemma, or {@code null} if none found
     */
    @Nullable
    static String extractLemmaFromMatch(String matchText) {
        String lemma = extractLastLemma(matchText);
        return lemma != null ? lemma.toLowerCase() : null;
    }

    /**
     * Extract lemma from matched text with a plain-text fallback.
     * Tries {@link #extractLemmaFromMatch(String)} first; if that returns null or blank,
     * falls back to extracting the last whitespace-separated token from the raw identity
     * string (e.g. a plain "word pos" grouping format).
     *
     * @param identity the identity string from a {@link nl.inl.blacklab.search.results.HitGroup}
     * @return the extracted lemma, or an empty string if nothing could be determined
     */
    static String extractLemmaWithFallback(String identity) {
        String lemma = extractLemmaFromMatch(identity);
        if (lemma == null || lemma.isBlank()) {
            String trimmed = identity.trim();
            int lastSpace = trimmed.lastIndexOf(' ');
            lemma = lastSpace >= 0 ? trimmed.substring(lastSpace + 1) : trimmed;
        }
        return lemma;
    }

    /**
     * Extract POS tag from matched text (XML format).
     * Tries xpos first, falls back to upos.
     *
     * @return the POS tag string, or {@code null} if not found
     */
    @Nullable
    static String extractPosFromMatch(@Nullable String matchText) {
        if (matchText == null || matchText.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher m = XPOS_ATTR.matcher(matchText);
        if (m.find()) {
            return m.group(1);
        }
        m = UPOS_ATTR.matcher(matchText);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Trim left/match/right plain-text parts to a single sentence.
     * Scans the left context backward for the last sentence boundary and
     * the right context forward for the first sentence boundary.
     */
    static String trimToSentence(String left, String match, String right) {
        String trimmedLeft = trimLeftAtSentenceBoundary(left);
        String trimmedRight = trimRightAtSentenceBoundary(right);
        String assembled = (trimmedLeft.isEmpty() ? "" : trimmedLeft + " ") + match
                         + (trimmedRight.isEmpty() ? "" : " " + trimmedRight);
        return detokenize(assembled);
    }

    /** Keep only the portion of left-context text AFTER the last sentence boundary. */
    static String trimLeftAtSentenceBoundary(String text) {
        if (text == null || text.isEmpty()) return "";
        java.util.regex.Matcher m = SENTENCE_BOUND_LEFT.matcher(text);
        int lastEnd = 0;
        while (m.find()) lastEnd = m.end();
        return lastEnd > 0 ? text.substring(lastEnd).trim() : text.trim();
    }

    /** Keep only the portion of right-context text UP TO AND INCLUDING the first sentence boundary. */
    static String trimRightAtSentenceBoundary(String text) {
        if (text == null || text.isEmpty()) return "";
        java.util.regex.Matcher m = SENTENCE_BOUND_RIGHT.matcher(text);
        if (m.find()) return text.substring(0, m.end()).trim();
        return text.trim();
    }

    /** Keep only content after the last &lt;s&gt; open tag in the left-context XML. */
    static String trimLeftXmlAtSentence(String xml) {
        if (xml == null || xml.isEmpty()) return "";
        java.util.regex.Matcher m = XML_SENTENCE_OPEN.matcher(xml);
        int lastTagEnd = -1;
        while (m.find()) lastTagEnd = m.end();
        return (lastTagEnd >= 0) ? xml.substring(lastTagEnd) : xml;
    }

    /** Keep only content before the first &lt;/s&gt; close tag in the right-context XML. */
    static String trimRightXmlAtSentence(String xml) {
        if (xml == null || xml.isEmpty()) return "";
        java.util.regex.Matcher m = XML_SENTENCE_CLOSE.matcher(xml);
        return m.find() ? xml.substring(0, m.start()) : xml;
    }

    static String extractPlainTextFromXml(String xmlSnippet) {
        if (xmlSnippet == null || xmlSnippet.isEmpty()) {
            return "";
        }
        String text = xmlSnippet.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
        return detokenize(text);
    }

    /**
     * Remove spurious spaces introduced by whitespace-separated tokenization (e.g. "word ," → "word,").
     *
     * @return the detokenized string, or {@code null} if {@code text} is {@code null}
     */
    @Nullable
    static String detokenize(@Nullable String text) {
        if (text == null || text.isEmpty()) return text;
        return text
            .replaceAll(" +([.,!?:;)\\]])", "$1")
            .replaceAll("([({\\[]) +", "$1");
    }

    /**
     * Extract collocate lemma from XML by labeled position.
     * @param xmlSnippet The XML snippet containing the full sentence
     * @param position The 1-based position of the token to extract; callers are responsible
     *                 for ensuring {@code position >= 1} before calling this method.
     * @return the lemma at the given position, or {@code null} if not found
     */
    @Nullable
    static String extractCollocateFromXmlByPosition(String xmlSnippet, int position) {
        if (xmlSnippet == null || xmlSnippet.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher m = LEMMA_ATTR.matcher(xmlSnippet);
        int count = 0;
        while (m.find()) {
            count++;
            if (count == position) {
                return m.group(1);
            }
        }
        return null;
    }

    // ==================== Consolidated clean API ====================

    /**
     * Extract a word by position from plain whitespace-separated text.
     *
     * @param text      whitespace-separated token string
     * @param position  1-based position of the word to extract
     * @return the word at that position, or {@code null} if out of range
     */
    @Nullable
    static String extractPlainTextTokenAt(String text, int position) {
        if (text == null || text.isEmpty() || position < 1) return null;
        String[] words = text.trim().split("\\s+");
        if (position > words.length) return null;
        return words[position - 1];
    }



    /**
     * Normalises the concordance parts array, throwing {@link IllegalArgumentException} when the
     * concordance is absent or malformed.  Each element in the returned array is guaranteed
     * non-null (empty-string instead of null).
     *
     * @param conc the concordance from BlackLab
     * @return a 3-element array [left, match, right]
     * @throws IllegalArgumentException if {@code conc} is null or parts are missing/malformed
     */
    static String[] safeParts(nl.inl.blacklab.search.Concordance conc) {
        if (conc == null) {
            throw new IllegalArgumentException("Concordance must not be null");
        }
        String[] parts = conc.parts();
        if (parts == null || parts.length < 3) {
            throw new IllegalArgumentException(
                "Concordance parts malformed or missing (length=" + (parts == null ? "null" : parts.length) + ")");
        }
        return new String[]{
            parts[0] != null ? parts[0] : "",
            parts[1] != null ? parts[1] : "",
            parts[2] != null ? parts[2] : ""
        };
    }

    /** Returns the last {@code lemma="..."} value in {@code xml}, or {@code null}. */
    @Nullable
    static String extractLastLemma(String xml) {
        if (xml == null || xml.isEmpty()) return null;
        java.util.regex.Matcher m = LEMMA_ATTR.matcher(xml);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }
}

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
    private static final java.util.regex.Pattern LEMMA_ATTR_RELAXED  = java.util.regex.Pattern.compile("lemma=[\"']([^\"']+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern XPOS_ATTR       = java.util.regex.Pattern.compile("xpos=\"([^\"]+)\"");
    private static final java.util.regex.Pattern UPOS_ATTR       = java.util.regex.Pattern.compile("upos=\"([^\"]+)\"");
    private static final java.util.regex.Pattern SENT_BOUND_LEFT  = java.util.regex.Pattern.compile("[.!?]\\s+(?=[A-Z]|$)");
    private static final java.util.regex.Pattern SENT_BOUND_RIGHT = java.util.regex.Pattern.compile("[.!?](?=\\s+[A-Z]|\\s*$)");
    private static final java.util.regex.Pattern XML_SENT_OPEN   = java.util.regex.Pattern.compile("<s(?:\\s[^>]*)?>", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern XML_SENT_CLOSE  = java.util.regex.Pattern.compile("</s>", java.util.regex.Pattern.CASE_INSENSITIVE);

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
    static String extractPosFromMatch(String matchText) {
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
        java.util.regex.Matcher m = SENT_BOUND_LEFT.matcher(text);
        int lastEnd = 0;
        while (m.find()) lastEnd = m.end();
        return lastEnd > 0 ? text.substring(lastEnd).trim() : text.trim();
    }

    /** Keep only the portion of right-context text UP TO AND INCLUDING the first sentence boundary. */
    static String trimRightAtSentenceBoundary(String text) {
        if (text == null || text.isEmpty()) return "";
        java.util.regex.Matcher m = SENT_BOUND_RIGHT.matcher(text);
        if (m.find()) return text.substring(0, m.end()).trim();
        return text.trim();
    }

    /** Keep only content after the last &lt;s&gt; open tag in the left-context XML. */
    static String trimLeftXmlAtSentence(String xml) {
        if (xml == null || xml.isEmpty()) return "";
        java.util.regex.Matcher m = XML_SENT_OPEN.matcher(xml);
        int lastTagEnd = -1;
        while (m.find()) lastTagEnd = m.end();
        return (lastTagEnd >= 0) ? xml.substring(lastTagEnd) : xml;
    }

    /** Keep only content before the first &lt;/s&gt; close tag in the right-context XML. */
    static String trimRightXmlAtSentence(String xml) {
        if (xml == null || xml.isEmpty()) return "";
        java.util.regex.Matcher m = XML_SENT_CLOSE.matcher(xml);
        return m.find() ? xml.substring(0, m.start()) : xml;
    }

    static String extractPlainTextFromXml(String xmlSnippet) {
        if (xmlSnippet == null || xmlSnippet.isEmpty()) {
            return "";
        }
        String text = xmlSnippet.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
        return detokenize(text);
    }

    /** Remove spurious spaces introduced by whitespace-separated tokenization (e.g. "word ," → "word,"). */
    static String detokenize(String text) {
        if (text == null || text.isEmpty()) return text;
        return text
            .replaceAll(" +([.,!?:;)\\]])", "$1")
            .replaceAll("([({\\[]) +", "$1");
    }

    /**
     * Extract collocate lemma from XML by labeled position.
     * @param xmlSnippet The XML snippet containing the full sentence
     * @param position The 1-based position of the token to extract (from findLabelTokenIndex)
     * @return the lemma at the given position, or {@code null} if not found
     */
    @Nullable
    static String extractCollocateFromXmlByPosition(String xmlSnippet, int position) {
        if (xmlSnippet == null || xmlSnippet.isEmpty() || position < 1) {
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

    @Nullable
    static String extractHeadword(String bcqlPattern) {
        java.util.regex.Matcher m = LEMMA_ATTR_RELAXED.matcher(bcqlPattern);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

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
     * Find the position of a labeled capture group (e.g., "2:") in a BCQL pattern.
     * Returns the 1-based position of that token in the pattern.
     *
     * Example: "1:[xpos="NN.*"] [lemma="be|..."] 2:[xpos="JJ.*"]"
     * - "1:" is at position 1
     * - "[lemma=...]" (unlabeled) is at position 2
     * - "2:" is at position 3
     */
    static int findLabelTokenIndex(String pattern, int label) {
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
        return -1;
    }

    // ==================== Consolidated clean API ====================

    /**
     * Extract the collocate (labeled {@code 2:}) lemma from a BCQL match snippet.
     * Finds the last lemma attribute, which corresponds to the collocate in most patterns.
     *
     * @param matchXml The match XML (parts[1] from a concordance)
     * @return The collocate lemma (original case), or {@code null}
     */
    @Nullable
    static String extractCollocateLemma(String matchXml) {
        return extractLastLemma(matchXml);
    }

    /** Returns the last {@code lemma="..."} value in {@code xml}, or {@code null}. */
    private static String extractLastLemma(String xml) {
        if (xml == null || xml.isEmpty()) return null;
        java.util.regex.Matcher m = LEMMA_ATTR.matcher(xml);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }
}

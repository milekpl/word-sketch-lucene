package pl.marcinmilkowski.word_sketch.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods for parsing BlackLab XML snippets and extracting collocates.
 */
public class BlackLabSnippetParser {
    private static final Logger logger = LoggerFactory.getLogger(BlackLabSnippetParser.class);

    static final java.util.regex.Pattern LEMMA_ATTR      = java.util.regex.Pattern.compile("lemma=\"([^\"]+)\"");
    static final java.util.regex.Pattern LEMMA_ATTR_ANY  = java.util.regex.Pattern.compile("lemma=[\"']([^\"']+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE);
    static final java.util.regex.Pattern XPOS_ATTR       = java.util.regex.Pattern.compile("xpos=\"([^\"]+)\"");
    static final java.util.regex.Pattern UPOS_ATTR       = java.util.regex.Pattern.compile("upos=\"([^\"]+)\"");
    static final java.util.regex.Pattern SENT_BOUND_LEFT  = java.util.regex.Pattern.compile("[.!?]\\s+(?=[A-Z]|$)");
    static final java.util.regex.Pattern SENT_BOUND_RIGHT = java.util.regex.Pattern.compile("[.!?](?=\\s+[A-Z]|\\s*$)");
    static final java.util.regex.Pattern XML_SENT_OPEN   = java.util.regex.Pattern.compile("<s(?:\\s[^>]*)?>", java.util.regex.Pattern.CASE_INSENSITIVE);
    static final java.util.regex.Pattern XML_SENT_CLOSE  = java.util.regex.Pattern.compile("</s>", java.util.regex.Pattern.CASE_INSENSITIVE);

    private BlackLabSnippetParser() {}

    /**
     * Extract lemma from matched text (XML format).
     * Finds all lemma="xxx" patterns and returns the last one (the collocate).
     */
    static String extractLemmaFromMatch(String matchText) {
        if (matchText == null || matchText.isEmpty()) {
            return null;
        }
        // Find all lemma="xxx" patterns and get the last one (the collocate)
        java.util.regex.Matcher m = LEMMA_ATTR.matcher(matchText);
        String lastLemma = null;
        while (m.find()) {
            lastLemma = m.group(1);
        }
        return lastLemma != null ? lastLemma.toLowerCase() : null;
    }

    /**
     * Extract POS tag from matched text (XML format).
     * Tries xpos first, falls back to upos.
     */
    static String extractPosFromMatch(String matchText) {
        if (matchText == null || matchText.isEmpty()) {
            return null;
        }
        // Try xpos first
        java.util.regex.Matcher m = XPOS_ATTR.matcher(matchText);
        if (m.find()) {
            return m.group(1);
        }
        // Fallback to upos
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
     * @param position The 1-based position of the token to extract (from findLabelPosition)
     */
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

    /**
     * Extract lemma from match text (space-separated tokens from Kwic.match()).
     * @param matchText Space-separated tokens from the match
     * @param position 1-based position within the match tokens
     */
    static String extractCollocateFromMatchText(String matchText, int position) {
        if (matchText == null || matchText.isEmpty() || position < 1) {
            return null;
        }
        String[] tokens = matchText.trim().split("\\s+");
        if (position > tokens.length) {
            return null;
        }
        return tokens[position - 1].toLowerCase();
    }

    static String extractHeadword(String bcqlPattern) {
        java.util.regex.Matcher m = LEMMA_ATTR_ANY.matcher(bcqlPattern);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Extract the collocate lemma from a concordance snippet (XML format).
     * Finds the last lemma attribute in the snippet.
     *
     * @deprecated Use {@link #extractHeadLemma(String)} or {@link #extractCollocateLemma(String)} for clarity.
     */
    @Deprecated
    static String extractCollocateFromSnippet(String snippet) {
        java.util.regex.Matcher m = LEMMA_ATTR.matcher(snippet);
        String lastLemma = null;
        while (m.find()) {
            lastLemma = m.group(1);
        }
        return lastLemma;
    }

    /**
     * Extract the collocate lemma from matched text using the labeled position.
     * @param matchOnly The matched text (parts[1] from concordance)
     * @param labelPos The 1-based position of the label (e.g., 3 for "2:" in pattern with 3 tokens)
     * @deprecated Prefer {@link #extractLemmaAt(String, int)} which has a clearer name.
     */
    @Deprecated
    static String extractCollocateFromMatch(String matchOnly, int labelPos) {
        if (matchOnly == null || matchOnly.isEmpty() || labelPos < 1) {
            return null;
        }
        java.util.regex.Matcher m = LEMMA_ATTR.matcher(matchOnly);
        int count = 0;
        while (m.find()) {
            count++;
            if (count == labelPos) {
                return m.group(1);
            }
        }
        return null;
    }

    /**
     * Extract the collocate from plain text (whitespace-separated words).
     * @param text The matched text (e.g., "theory is irrelevant")
     * @param position The 1-based position of the word to extract
     * @deprecated Prefer {@link #extractLemmaAt(String, int)} or positional XML extraction.
     */
    @Deprecated
    static String extractCollocateFromPlainText(String text, int position) {
        if (text == null || text.isEmpty() || position < 1) {
            return null;
        }
        String[] words = text.trim().split("\\s+");
        if (position > words.length) {
            return null;
        }
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
    static int findLabelPosition(String pattern, int label) {
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

    /**
     * Extract a specific token from a concordance snippet.
     * Snippet format is like: "...[word1]...[word2]..." where words have lemma attributes.
     * @deprecated Prefer {@link #extractLemmaAt(String, int)} which has clearer semantics.
     */
    @Deprecated
    static String extractTokenFromSnippet(String snippet, int position) {
        if (snippet == null || snippet.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher m = LEMMA_ATTR.matcher(snippet);
        int count = 0;
        while (m.find()) {
            count++;
            if (count == position) {
                return m.group(1);
            }
        }
        // If position is beyond the count, return the last one
        if (count > 0 && position > count) {
            m.reset();
            String last = null;
            while (m.find()) {
                last = m.group(1);
            }
            return last;
        }
        return null;
    }

    // ==================== Consolidated clean API ====================

    /**
     * Extract lemma at a specific 1-based token position from XML snippet.
     * This is the canonical method for positional lemma extraction.
     *
     * @param xml      XML snippet containing {@code lemma="..."} attributes
     * @param position 1-based token position
     * @return The lemma at that position, or {@code null} if not found
     */
    static String extractLemmaAt(String xml, int position) {
        return extractCollocateFromXmlByPosition(xml, position);
    }

    /**
     * Extract the head (labeled {@code 1:}) lemma from a BCQL match snippet.
     * Assumes the first lemma attribute in the match corresponds to the head token.
     *
     * @param matchXml The match XML (parts[1] from a concordance)
     * @return The head lemma in lowercase, or {@code null}
     */
    static String extractHeadLemma(String matchXml) {
        return extractLemmaAt(matchXml, 1);
    }

    /**
     * Extract the collocate (labeled {@code 2:}) lemma from a BCQL match snippet.
     * Finds the last lemma attribute, which corresponds to the collocate in most patterns.
     * For precise positional extraction, use {@link #extractLemmaAt(String, int)}.
     *
     * @param matchXml The match XML (parts[1] from a concordance)
     * @return The collocate lemma, or {@code null}
     */
    static String extractCollocateLemma(String matchXml) {
        return extractCollocateFromSnippet(matchXml);
    }
}

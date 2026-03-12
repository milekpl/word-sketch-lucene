package pl.marcinmilkowski.word_sketch.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utility for parsing CQL (Corpus Query Language) token syntax.
 *
 * <p>CQL patterns consist of token constraints in square brackets, e.g.
 * {@code [xpos="NN.*"] [xpos="JJ.*"]}. Both
 * {@link pl.marcinmilkowski.word_sketch.api.SketchHandlers} and
 * {@code pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader} need to
 * walk these bracket boundaries; this class provides the shared primitive.</p>
 *
 * <p>Note: {@code GrammarConfigLoader.deriveTokenPosition} extends this logic
 * to also detect numbered label prefixes ({@code 1:[...] 2:[...]}).</p>
 */
public final class CqlUtils {

    private CqlUtils() {}

    /**
     * Extracts a CQL attribute assignment of the form {@code attrName="value"} from a
     * constraint string (typically the interior of a {@code [...]} token block).
     *
     * <p>Examples:
     * <pre>
     *   extractConstraintAttribute("[xpos=\"NN.*\" & lemma=\"dog\"]", "xpos")  → "xpos=\"NN.*\""
     *   extractConstraintAttribute("[lemma=\"cat\"]", "xpos")                  → null
     * </pre>
     *
     * @param constraint a CQL constraint string, or {@code null}
     * @param attrName   the attribute name to look up (e.g. {@code "xpos"}, {@code "tag"})
     * @return the full {@code attrName="value"} fragment, or {@code null} if not found
     */
    public static String extractConstraintAttribute(String constraint, String attrName) {
        if (constraint == null) return null;
        Pattern p = Pattern.compile(Pattern.quote(attrName) + "=\"([^\"]*)\"");
        Matcher m = p.matcher(constraint);
        if (m.find()) {
            return attrName + "=\"" + m.group(1) + "\"";
        }
        return null;
    }

    /**
     * Escapes a string for embedding as a literal value inside a CQL regex attribute,
     * e.g. {@code [lemma="<value>"]}. Escapes backslashes and double-quotes.
     *
     * @param s the raw string; {@code null} returns {@code ""}
     * @return the escaped string
     */
    public static String escapeForRegex(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Splits a CQL pattern string into its top-level {@code [...]} token blocks.
     * Quoted strings ({@code "..."}) inside or outside brackets are skipped over
     * without being treated as token boundaries.
     *
     * <p>Examples:
     * <pre>
     *   "[xpos=\"NN.*\"] [xpos=\"JJ.*\"]"  →  ["[xpos=\"NN.*\"]", "[xpos=\"JJ.*\"]"]
     *   "1:[xpos=\"NN.*\"] 2:[xpos=\"JJ.*\"]"  →  ["[xpos=\"NN.*\"]", "[xpos=\"JJ.*\"]"]
     * </pre>
     *
     * @param pattern a CQL pattern, or {@code null} / blank
     * @return ordered list of {@code [constraint]} substrings; empty if none found
     */
    public static List<String> splitCqlTokens(String pattern) {
        List<String> tokens = new ArrayList<>();
        if (pattern == null || pattern.isBlank()) {
            return tokens;
        }
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '[') {
                // Use depth-counting to handle nested brackets like [[a] & [b]]
                int start = i;
                int depth = 0;
                while (i < pattern.length()) {
                    char ch = pattern.charAt(i);
                    if (ch == '"') {
                        // Skip quoted string inside brackets
                        i++;
                        while (i < pattern.length() && pattern.charAt(i) != '"') {
                            if (pattern.charAt(i) == '\\') i++; // skip escape
                            i++;
                        }
                    } else if (ch == '[') {
                        depth++;
                    } else if (ch == ']') {
                        depth--;
                        if (depth == 0) {
                            tokens.add(pattern.substring(start, i + 1));
                            i++;
                            break;
                        }
                    }
                    i++;
                }
            } else if (c == '"') {
                // Skip quoted string outside brackets to avoid mistaking content for tokens
                i++;
                while (i < pattern.length() && pattern.charAt(i) != '"') {
                    if (pattern.charAt(i) == '\\') i++; // skip escape
                    i++;
                }
                i++; // closing quote
            } else {
                i++;
            }
        }
        return tokens;
    }

    /**
     * Substitutes a lemma constraint into a CQL pattern at the given token position.
     * The existing xpos/tag constraint at that position is preserved alongside the new
     * lemma constraint, e.g. {@code [xpos="NN.*"]} at position 1 with lemma "theory"
     * becomes {@code [lemma="theory" & xpos="NN.*"]}.
     *
     * @param pattern  CQL pattern to substitute into; returned unchanged if null or position is out of range
     * @param lemma    lemma value to inject; returned unchanged if null or blank
     * @param position 1-based token position to substitute
     * @return the substituted pattern, or the original pattern when inputs are invalid
     */
    public static String substituteAtPosition(String pattern, String lemma, int position) {
        if (pattern == null || lemma == null || lemma.isBlank() || position < 1) {
            return pattern;
        }
        List<String> tokens = splitCqlTokens(pattern);
        if (position > tokens.size()) {
            return pattern;
        }
        tokens.set(position - 1, injectLemmaConstraint(tokens.get(position - 1), lemma));
        return String.join(" ", tokens);
    }

    /**
     * Injects a lemma constraint into the existing xpos/tag constraint in a CQL token.
     * E.g. {@code "[xpos=\"NN.*\"]"} + {@code "theory"} → {@code "[lemma=\"theory\" & xpos=\"NN.*\"]"}.
     *
     * @param existingConstraint the original CQL token constraint (including brackets)
     * @param lemma              the lemma value to inject
     * @return the updated constraint with both lemma and any original POS constraints
     */
    public static String injectLemmaConstraint(String existingConstraint, String lemma) {
        String xposPattern = extractConstraintAttribute(existingConstraint, "xpos");
        String tagPattern = extractConstraintAttribute(existingConstraint, "tag");
        StringBuilder sb = new StringBuilder();
        sb.append("[lemma=\"").append(escapeForRegex(lemma)).append("\"");
        if (xposPattern != null) {
            sb.append(" & ").append(xposPattern);
        }
        if (tagPattern != null) {
            sb.append(" & ").append(tagPattern);
        }
        sb.append("]");
        return sb.toString();
    }
    // BCQL pattern utilities (moved from BcqlPatternUtils in query package)

    private static final java.util.regex.Pattern LEMMA_ATTR_RELAXED =
            java.util.regex.Pattern.compile("lemma=[\"']([^\"']+)[\"']",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Extract the headword lemma from a BCQL pattern string.
     * Finds the first {@code lemma="..."} or {@code lemma='...'} attribute and returns its value.
     *
     * @param bcqlPattern the BCQL pattern string
     * @return the lemma value, or {@code null} if no lemma attribute is present
     */
    @org.jspecify.annotations.Nullable
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
        return -1;
    }
}

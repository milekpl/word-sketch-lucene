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
 * {@code pl.marcinmilkowski.word_sketch.utils.PatternSubstitution} and
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
}

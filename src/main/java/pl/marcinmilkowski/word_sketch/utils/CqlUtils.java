package pl.marcinmilkowski.word_sketch.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared utility for parsing CQL (Corpus Query Language) token syntax.
 *
 * <p>CQL patterns consist of token constraints in square brackets, e.g.
 * {@code [xpos="NN.*"] [xpos="JJ.*"]}. Both
 * {@code pl.marcinmilkowski.word_sketch.api.PatternSubstitution} and
 * {@code pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader} need to
 * walk these bracket boundaries; this class provides the shared primitive.</p>
 *
 * <p>Note: {@code GrammarConfigLoader.deriveTokenPosition} extends this logic
 * to also detect numbered label prefixes ({@code 1:[...] 2:[...]}).</p>
 */
public final class CqlUtils {

    private CqlUtils() {}

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

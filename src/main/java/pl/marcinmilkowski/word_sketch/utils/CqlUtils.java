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
    public static List<String> splitCqlTokens(String pattern) {
        List<String> tokens = new ArrayList<>();
        if (pattern == null || pattern.isBlank()) {
            return tokens;
        }
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '[') {
                int end = pattern.indexOf(']', i);
                if (end > i) {
                    tokens.add(pattern.substring(i, end + 1));
                    i = end + 1;
                } else {
                    i++;
                }
            } else if (c == '"') {
                // Skip quoted string to avoid mistaking embedded content for tokens
                int end = pattern.indexOf('"', i + 1);
                i = (end > i) ? end + 1 : i + 1;
            } else {
                i++;
            }
        }
        return tokens;
    }
}

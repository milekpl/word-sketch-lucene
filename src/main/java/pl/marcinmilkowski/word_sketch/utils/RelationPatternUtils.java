package pl.marcinmilkowski.word_sketch.utils;

import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.model.PosGroup;
import pl.marcinmilkowski.word_sketch.utils.CqlUtils;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * Pure static utility class for building BCQL pattern strings from a {@link RelationConfig}.
 *
 * <p>Pattern-building logic was extracted here from {@link RelationConfig} so that the
 * config record remains a plain data carrier. All methods are stateless functions of their
 * arguments; none require instantiation.</p>
 */
public final class RelationPatternUtils {

    private static final String JJ_PREFIX = "JJ";
    private static final String VB_PREFIX = "VB";
    private static final String NN_PREFIX = "NN";
    private static final String RB_PREFIX = "RB";

    private RelationPatternUtils() {}

    /**
     * Builds the BCQL pattern with both headword and collocate lemmas substituted.
     *
     * @param config         the relation providing pattern, headPosition, and collocatePosition
     * @param headword       the lemma for the head position
     * @param collocateLemma the lemma for the collocate position
     * @return the fully-substituted BCQL pattern string
     */
    public static String buildFullPattern(RelationConfig config, @Nullable String headword, String collocateLemma) {
        String withHead = buildFullPattern(config, headword);
        if (collocateLemma == null || collocateLemma.isBlank()) return withHead;
        return CqlUtils.substituteAtPosition(withHead, collocateLemma, config.collocatePosition());
    }

    /**
     * Builds the BCQL pattern with only the headword substituted.
     *
     * <p><strong>Null/blank passthrough:</strong> when {@code headword} is {@code null} or blank,
     * the original pattern is returned unchanged.</p>
     *
     * @param config   the relation providing pattern and headPosition
     * @param headword the lemma to substitute; if null/blank, the original pattern is returned
     * @return the substituted pattern, or the original pattern when headword is null/blank
     * @throws NullPointerException if {@code config.pattern()} is null
     */
    public static String buildFullPattern(RelationConfig config, @Nullable String headword) {
        Objects.requireNonNull(config.pattern(),
            "RelationConfig '" + config.id() + "' has no pattern — check grammar config");
        if (headword == null || headword.isBlank()) return config.pattern();
        return CqlUtils.substituteAtPosition(config.pattern(), headword, config.headPosition());
    }

    /**
     * Returns the CQL pattern used for reverse collocate lookup, derived from the collocate POS group.
     *
     * @param config the relation providing relationType and collocatePosGroup
     * @throws IllegalStateException if relationType is absent or the POS group has no known reverse pattern
     */
    public static String buildCollocateReversePattern(RelationConfig config) {
        if (config.relationType().isEmpty()) {
            throw new IllegalStateException(
                "Cannot determine collocate reverse pattern: relationType is absent for relation '"
                    + config.id() + "'");
        }
        return switch (config.collocatePosGroup()) {
            case ADJ  -> "[xpos=\"" + JJ_PREFIX + ".*\"]";
            case VERB -> "[xpos=\"" + VB_PREFIX + ".*\"]";
            case NOUN -> "[xpos=\"" + NN_PREFIX + ".*\"]";
            case ADV  -> "[xpos=\"" + RB_PREFIX + ".*\"]";
            default   -> throw new IllegalStateException(
                "No reverse pattern defined for collocate POS group: " + config.collocatePosGroup()
                    + " (relation: '" + config.id() + "')");
        };
    }

    /**
     * Computes the collocate {@link PosGroup} from a raw BCQL pattern string.
     * Called by {@link GrammarConfigLoader} at construction time to populate
     * {@link RelationConfig#collocatePosGroup()}.
     *
     * @param pattern the raw BCQL pattern; may be null (returns {@link PosGroup#OTHER})
     * @return the inferred POS group
     */
    public static PosGroup computeCollocatePosGroup(String pattern) {
        if (pattern == null) return PosGroup.OTHER;
        String pat = pattern.toLowerCase(Locale.ROOT);
        String target = extractLabelContent(pat, 2);
        if (target == null) target = pat;
        PosGroup result = resolvePosGroupFromPrefix(target, "xpos=");
        return result != null ? result : PosGroup.OTHER;
    }

    /** Extract the bracket content of the nth labeled position (e.g. "2:[...]"). */
    private static String extractLabelContent(String pat, int label) {
        String prefix = label + ":[";
        int idx = pat.indexOf(prefix);
        if (idx < 0) return null;
        int start = idx + prefix.length() - 1;
        int depth = 0;
        for (int i = start; i < pat.length(); i++) {
            if (pat.charAt(i) == '[') depth++;
            else if (pat.charAt(i) == ']') { if (--depth == 0) return pat.substring(start, i + 1); }
        }
        return null;
    }

    private static PosGroup resolvePosGroupFromPrefix(String s, String attr) {
        String q = attr + "\"";
        if (s.contains(q + "jj") || s.contains(attr + "jj")) return PosGroup.ADJ;
        if (s.contains(q + "vb") || s.contains(attr + "vb")) return PosGroup.VERB;
        if (s.contains(q + "nn") || s.contains(attr + "nn")) return PosGroup.NOUN;
        if (s.contains(q + "rb") || s.contains(attr + "rb")) return PosGroup.ADV;
        if (s.contains(q + "in") || s.contains(attr + "in")) return PosGroup.OTHER;
        if (s.contains(q + "rp") || s.contains(attr + "rp")
         || s.contains(q + "to") || s.contains(attr + "to")) return PosGroup.OTHER;
        return null;
    }
}

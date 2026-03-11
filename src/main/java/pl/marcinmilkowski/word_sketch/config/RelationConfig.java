package pl.marcinmilkowski.word_sketch.config;

import com.alibaba.fastjson2.JSONObject;
import pl.marcinmilkowski.word_sketch.model.PosGroup;
import pl.marcinmilkowski.word_sketch.model.RelationType;
import pl.marcinmilkowski.word_sketch.utils.CqlUtils;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Relation configuration record.
 */
public record RelationConfig(
    String id,
    String name,
    String description,
    String pattern,
    int headPosition,
    int collocatePosition,
    boolean dual,
    int defaultSlop,
    /** Empty when the grammar JSON omits or has an unrecognised {@code relation_type} field. */
    Optional<RelationType> relationType,
    boolean explorationEnabled
) {
    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        if (name != null) obj.put("name", name);
        if (description != null) obj.put("description", description);
        if (pattern != null) obj.put("pattern", pattern);
        obj.put("head_position", headPosition);
        obj.put("collocate_position", collocatePosition);
        obj.put("dual", dual);
        obj.put("default_slop", defaultSlop);
        relationType.ifPresent(rt -> obj.put("relation_type", rt.name()));
        obj.put("exploration_enabled", explorationEnabled);
        return obj;
    }

    /**
     * Get the BCQL pattern with both headword and collocate lemmas substituted.
     * Delegates to {@link #getFullPattern(String)} for the head, then injects
     * the collocate lemma at {@link #collocatePosition}.
     *
     * @param headword       the lemma for the head position
     * @param collocateLemma the lemma for the collocate position
     * @return the fully-substituted BCQL pattern string
     */
    public String getFullPattern(String headword, String collocateLemma) {
        String withHead = getFullPattern(headword);
        if (collocateLemma == null || collocateLemma.isBlank()) return withHead;
        return CqlUtils.substituteAtPosition(withHead, collocateLemma, collocatePosition);
    }

    /**
     * Get the BCQL pattern with headword substituted.
     * For BCQL format: replaces the constraint at head_position with [lemma="headword" & original_constraint]
     *
     * @param headword the lemma to substitute at the head position; if {@code null} or blank,
     *                 the unmodified pattern is returned unchanged
     * @return the substituted pattern, or the original pattern when headword is null/blank
     */
    public String getFullPattern(String headword) {
        if (pattern == null) return null;
        if (headword == null || headword.isBlank()) return pattern;

        // Parse the pattern and substitute the headword at head_position
        return CqlUtils.substituteAtPosition(pattern, headword, headPosition);
    }



    /**
     * Returns the CQL pattern used for reverse collocate lookup, derived from the collocate POS group.
     * Each POS group maps to a single, specific pattern so that reverse lookups target exactly the
     * right tokens and never produce an incoherent union (e.g. {@code NN.*|VB.*}).
     *
     * @throws IllegalStateException if {@code relationType()} is empty or the collocate POS
     *         group cannot be mapped to a unique pattern.
     */
    public String collocateReversePattern() {
        if (relationType().isEmpty()) {
            throw new IllegalStateException(
                "Cannot determine collocate reverse pattern: relationType is absent for relation '" + id + "'");
        }
        return switch (collocatePosGroup()) {
            case ADJ  -> "[xpos=\"JJ.*\"]";
            case VERB -> "[xpos=\"VB.*\"]";
            case NOUN -> "[xpos=\"NN.*\"]";
            case ADV  -> "[xpos=\"RB.*\"]";
            default   -> throw new IllegalStateException(
                "No reverse pattern defined for collocate POS group: " + collocatePosGroup()
                    + " (relation: '" + id + "')");
        };
    }

    /**
     * Derive the collocate POS group from the pattern.
     * Looks specifically at the 2: labelled position so that multi-token patterns
     * like "1:[xpos="NN.*"] 2:[xpos="JJ.*"]" correctly return "adj" not "noun".
     * Supports both xpos= (current grammar) and tag= (legacy format).
     */
    public PosGroup collocatePosGroup() {
        if (pattern == null) return PosGroup.OTHER;
        String pat = pattern.toLowerCase(Locale.ROOT);
        // Prefer the 2: labeled bracket; fall back to whole pattern for single-token patterns
        String target = extractLabelContent(pat, 2);
        if (target == null) target = pat;
        return posGroupFromConstraint(target);
    }

    /** Extract the bracket content of the nth labeled position (e.g. "2:[...]"). */
    private String extractLabelContent(String pat, int label) {
        String prefix = label + ":[";
        int idx = pat.indexOf(prefix);
        if (idx < 0) return null;
        int start = idx + prefix.length() - 1; // points at '['
        int depth = 0;
        for (int i = start; i < pat.length(); i++) {
            if (pat.charAt(i) == '[') depth++;
            else if (pat.charAt(i) == ']') { if (--depth == 0) return pat.substring(start, i + 1); }
        }
        return null;
    }

    /**
     * Map a raw CQL constraint string to a {@link PosGroup} by scanning for known xpos/tag
     * attribute prefixes (e.g. {@code xpos="jj}, {@code tag="vb}).
     *
     * <p>This intentionally uses raw string scanning rather than {@link PosGroup#fromString}
     * because the input is a CQL bracket expression (e.g. {@code [xpos="JJ.*"]}), not a
     * value-label string like {@code "adj"}. {@code PosGroup.fromString} maps canonical value
     * labels to enum constants; it is not applicable here.
     */
    private PosGroup posGroupFromConstraint(String s) {
        PosGroup result = posGroupForPrefix(s, "xpos=");
        // tag= fallback supports legacy grammar files that use "tag=" instead of "xpos=".
        // Kept to remain compatible with older grammar configs not yet migrated to xpos=.
        if (result == null) result = posGroupForPrefix(s, "tag=");
        return result != null ? result : PosGroup.OTHER;
    }

    /**
     * Maps a CQL constraint string to a {@link PosGroup} by scanning for the given attribute
     * prefix (e.g. {@code "xpos="} for Penn Treebank, {@code "tag="} for legacy grammars).
     * Returns {@code null} when no matching prefix is found, so the caller can try a fallback.
     */
    private static PosGroup posGroupForPrefix(String s, String attr) {
        String q = attr + "\"";  // quoted form, e.g. xpos="
        if (s.contains(q + "jj") || s.contains(attr + "jj")) return PosGroup.ADJ;
        if (s.contains(q + "vb") || s.contains(attr + "vb")) return PosGroup.VERB;
        if (s.contains(q + "nn") || s.contains(attr + "nn")) return PosGroup.NOUN;
        if ("tag=".equals(attr)
         && (s.contains(q + "pos") || s.contains(attr + "pos"))) return PosGroup.NOUN;
        if (s.contains(q + "rb") || s.contains(attr + "rb")) return PosGroup.ADV;
        if (s.contains(q + "in") || s.contains(attr + "in")) return PosGroup.OTHER;
        if (s.contains(q + "rp") || s.contains(attr + "rp")
         || s.contains(q + "to") || s.contains(attr + "to")) return PosGroup.OTHER;
        return null;
    }

    /**
     * Extract the dependency relation (deprel) from the pattern.
     * For DEP relations, looks for deprel="xxx" attribute constraint in the pattern.
     * If not found, extracts from the relation ID (e.g., "dep_amod" -> "amod").
     *
     * @return the deprel string (e.g. {@code "amod"}), or {@code null} when the relation
     *         type is not {@link RelationType#DEP} or no deprel can be derived
     */
    public @org.jspecify.annotations.Nullable String getDeprel() {
        if (pattern == null || !RelationType.DEP.equals(relationType().orElse(null))) {
            return null;
        }
        // Look for deprel="xxx" or deprel='xxx' attribute constraint
        Pattern p = Pattern.compile("deprel=[\"']([^\"']+)[\"']");
        Matcher m = p.matcher(pattern);
        if (m.find()) {
            return m.group(1);
        }
        // Fallback: extract from relation ID (e.g., "dep_amod" -> "amod")
        if (id != null && id.startsWith("dep_")) {
            return id.substring(4);
        }
        return null;
    }

}

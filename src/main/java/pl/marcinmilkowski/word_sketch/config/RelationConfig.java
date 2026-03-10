package pl.marcinmilkowski.word_sketch.config;

import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.utils.CqlUtils;

import java.util.List;
import java.util.Locale;
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
    /** May be {@code null} when the grammar JSON omits the {@code relation_type} field. */
    RelationType relationType,
    boolean explorationEnabled
) {
    private static final Logger logger = LoggerFactory.getLogger(RelationConfig.class);

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
        if (relationType != null) obj.put("relation_type", relationType.name());
        obj.put("exploration_enabled", explorationEnabled);
        return obj;
    }

    /**
     * Get the BCQL pattern with headword substituted.
     * For BCQL format: replaces the constraint at head_position with [lemma="headword" & original_constraint]
     */
    public String getFullPattern(String headword) {
        if (pattern == null) return null;
        if (headword == null || headword.isBlank()) return pattern;

        // Parse the pattern and substitute the headword at head_position
        return substituteHeadword(pattern, headword, headPosition);
    }

    /**
     * Substitute the headword into the BCQL pattern at the specified position.
     * E.g., pattern "[xpos=\"NN.*\"] [xpos=\"JJ.*\"]" with head at position 1
     * becomes "[lemma=\"theory\" & xpos=\"NN.*\"] [xpos=\"JJ.*\"]"
     */
    private static String substituteHeadword(String pattern, String headword, int headPosition) {
        if (pattern == null || headword == null || headPosition < 1) {
            return pattern;
        }

        List<String> tokens = CqlUtils.splitCqlTokens(pattern);
        if (headPosition > tokens.size()) {
            return pattern;
        }

        tokens.set(headPosition - 1, mergeLemmaConstraint(tokens.get(headPosition - 1), headword));
        return String.join(" ", tokens);
    }

    /**
     * Merge lemma constraint with existing xpos/pos constraint.
     * E.g., "[xpos=\"NN.*\"]" + "theory" -> "[lemma=\"theory\" & xpos=\"NN.*\"]"
     */
    private static String mergeLemmaConstraint(String existingConstraint, String headword) {
        // Parse existing constraint to extract xpos/pos tags
        String xposPattern = extractConstraintAttribute(existingConstraint, "xpos");
        String posPattern = extractConstraintAttribute(existingConstraint, "tag");

        // Build new constraint with lemma
        StringBuilder sb = new StringBuilder();
        sb.append("[lemma=\"").append(escapeForRegex(headword)).append("\"");

        if (xposPattern != null) {
            sb.append(" & ").append(xposPattern);
        }
        if (posPattern != null) {
            sb.append(" & ").append(posPattern);
        }

        sb.append("]");
        return sb.toString();
    }

    private static String extractConstraintAttribute(String constraint, String attrName) {
        if (constraint == null) return null;
        Pattern p = Pattern.compile(attrName + "=\"([^\"]*)\"");
        Matcher m = p.matcher(constraint);
        if (m.find()) {
            return attrName + "=\"" + m.group(1) + "\"";
        }
        return null;
    }

    private static String escapeForRegex(String s) {
        return CqlUtils.escapeForRegex(s);
    }

    /**
     * Returns the CQL pattern used for reverse collocate lookup, derived from the collocate POS group.
     * Adjective relations reverse-look up {@code [xpos="JJ.*"]}; noun/verb relations use
     * {@code [xpos="NN.*|VB.*"]}.
     */
    public String collocateReversePattern() {
        return switch (collocatePosGroup()) {
            case ADJ -> "[xpos=\"JJ.*\"]";
            default  -> "[xpos=\"NN.*|VB.*\"]";
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
        // xpos (Penn Treebank — used by current grammar)
        if (s.contains("xpos=\"jj") || s.contains("xpos=jj")) return PosGroup.ADJ;
        if (s.contains("xpos=\"vb") || s.contains("xpos=vb")) return PosGroup.VERB;
        if (s.contains("xpos=\"nn") || s.contains("xpos=nn")) return PosGroup.NOUN;
        if (s.contains("xpos=\"rb") || s.contains("xpos=rb")) return PosGroup.ADV;
        if (s.contains("xpos=\"in") || s.contains("xpos=in")) return PosGroup.OTHER;
        if (s.contains("xpos=\"rp") || s.contains("xpos=rp")
         || s.contains("xpos=\"to") || s.contains("xpos=to")) return PosGroup.OTHER;
        // legacy: tag= attribute support — use xpos= in new grammars
        if (s.contains("tag=\"jj") || s.contains("tag=jj")) return PosGroup.ADJ;
        if (s.contains("tag=\"vb") || s.contains("tag=vb")) return PosGroup.VERB;
        if (s.contains("tag=\"nn") || s.contains("tag=nn")
         || s.contains("tag=\"pos") || s.contains("tag=pos")) return PosGroup.NOUN;
        if (s.contains("tag=\"rb") || s.contains("tag=rb")) return PosGroup.ADV;
        if (s.contains("tag=\"in") || s.contains("tag=in")) return PosGroup.OTHER;
        if (s.contains("tag=\"rp") || s.contains("tag=rp")
         || s.contains("tag=\"to") || s.contains("tag=to")) return PosGroup.OTHER;
        return PosGroup.OTHER;
    }

    /**
     * Extract the dependency relation (deprel) from the pattern.
     * For DEP relations, looks for deprel="xxx" attribute constraint in the pattern.
     * If not found, extracts from the relation ID (e.g., "dep_amod" -> "amod").
     */
    public String getDeprel() {
        if (pattern == null || relationType != RelationType.DEP) {
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

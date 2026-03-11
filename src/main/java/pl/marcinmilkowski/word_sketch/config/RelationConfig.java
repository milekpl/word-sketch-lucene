package pl.marcinmilkowski.word_sketch.config;

import pl.marcinmilkowski.word_sketch.model.PosGroup;
import pl.marcinmilkowski.word_sketch.model.RelationType;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable data carrier for a single grammar relation deserialized from {@code relations.json}.
 *
 * <p>Pattern-building and POS-group inference logic lives in {@link RelationPatternBuilder},
 * keeping this record focused on data only. The sole remaining method ({@link #computeDeprel()})
 * is retained here because it is a simple derivation from {@link #pattern()} and {@link #id()}
 * that is only called during construction and has no callers outside this class.</p>
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
    boolean explorationEnabled,
    /** Pre-computed collocate POS group — cached at construction time so repeated calls are O(1). */
    PosGroup collocatePosGroup
) {
    /**
     * Extracts and computes the dependency relation (deprel) from the pattern.
     * For DEP relations, looks for deprel="xxx" attribute constraint in the pattern.
     * If not found, extracts from the relation ID (e.g., "dep_amod" -> "amod").
     *
     * @return the deprel string (e.g. {@code "amod"}), or {@code null} when the relation
     *         type is not {@link RelationType#DEP} or no deprel can be derived
     */
    public @org.jspecify.annotations.Nullable String computeDeprel() {
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

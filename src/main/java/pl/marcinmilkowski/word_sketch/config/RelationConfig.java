package pl.marcinmilkowski.word_sketch.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import pl.marcinmilkowski.word_sketch.model.PosGroup;
import pl.marcinmilkowski.word_sketch.model.RelationType;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable data carrier for a single grammar relation deserialized from {@code relations.json}.
 *
 * <p>Pattern-building and POS-group inference logic lives in {@link RelationUtils},
 * keeping this record focused on data only. The sole remaining method ({@link #deriveDeprel()})
 * is retained here because it is a simple derivation from {@link #pattern()} and {@link #id()};
 * it is part of the public API consumed by the API layer and related components.</p>
 */
public record RelationConfig(
    @NonNull String id,
    @Nullable String name,
    @Nullable String description,
    @NonNull String pattern,
    int headPosition,
    int collocatePosition,
    boolean dual,
    int defaultSlop,
    /** {@code null} when the grammar JSON omits or has an unrecognised {@code relation_type} field. */
    @Nullable RelationType rawRelationType,
    /** Pre-computed collocate POS group — cached at construction time so repeated calls are O(1). */
    PosGroup collocatePosGroup
) {
    private static final Pattern DEPREL_PATTERN = Pattern.compile("deprel=[\"']([^\"']+)[\"']");

    /**
     * Returns the relation type wrapped in an Optional; empty when the grammar JSON omitted
     * or had an unrecognised {@code relation_type} field.
     *
     * <p>The backing record component is {@link #rawRelationType()} — a plain {@code @Nullable}
     * field — avoiding the Optional-as-field antipattern. This method is the preferred accessor
     * for callers that need absent-safe handling.</p>
     */
    public Optional<RelationType> relationType() {
        return Optional.ofNullable(rawRelationType);
    }

    /**
     * Validates that this relation configuration is complete enough for exploration use.
     *
     * <p>Throws {@link IllegalArgumentException} if {@link #relationType()} is absent, so that
     * mis-configured relations are rejected with a clear message at the service layer rather
     * than failing midway through a corpus query.</p>
     *
     * @throws IllegalArgumentException if {@code relationType} is absent
     */
    public void validate() {
        if (relationType().isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid relation config: missing or unrecognised relation_type for '" + id + "'");
        }
    }

    /**
     * Extracts and computes the dependency relation (deprel) from the pattern.
     * For DEP relations, looks for deprel="xxx" attribute constraint in the pattern.
     * If not found, extracts from the relation ID (e.g., "dep_amod" -> "amod").
     *
     * <p><strong>Nullability design note:</strong> this method returns {@code @Nullable} rather
     * than {@code Optional} because it is a method-internal derivation used only during
     * construction and short-circuit logic (a {@code null} return signals "not applicable").
     * By contrast, {@link pl.marcinmilkowski.word_sketch.config.GrammarConfig#relation(String)}
     * returns {@code Optional} because it is a query-style accessor where the absent case is
     * a normal, expected outcome for callers.</p>
     *
     * @return the deprel string (e.g. {@code "amod"}), or {@code null} when the relation
     *         type is not {@link RelationType#DEP} or no deprel can be derived
     */
    public @org.jspecify.annotations.Nullable String deriveDeprel() {
        if (relationType().orElse(null) != RelationType.DEP) {
            return null;
        }
        // Look for deprel="xxx" or deprel='xxx' attribute constraint
        Matcher m = DEPREL_PATTERN.matcher(pattern);
        if (m.find()) {
            return m.group(1);
        }
        // Fallback: extract from relation ID (e.g., "dep_amod" -> "amod")
        if (id.startsWith("dep_")) {
            return id.substring(4);
        }
        return null;
    }

}

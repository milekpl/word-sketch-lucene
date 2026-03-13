package pl.marcinmilkowski.word_sketch.config;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

import pl.marcinmilkowski.word_sketch.model.PosGroup;
import pl.marcinmilkowski.word_sketch.model.RelationType;
import pl.marcinmilkowski.word_sketch.utils.CqlUtils;

/**
 * Shared utilities for relation identifier handling and BCQL pattern construction.
 *
 * <p>Covers alias resolution, grammar-config validation, reverse-pattern lookups,
 * and all stateless pattern-building functions operating on {@link RelationConfig}.</p>
 */
public final class RelationUtils {

    private static final String JJ_PREFIX = "JJ";
    private static final String VB_PREFIX = "VB";
    private static final String NN_PREFIX = "NN";
    private static final String RB_PREFIX = "RB";

    private RelationUtils() {}

    /**
     * Sentinel relation identifier used in comparison-endpoint responses to signal that
     * results are aggregated across all loaded grammatical relations rather than scoped to
     * a single relation. This constant lives in the config layer because it is a domain
     * concept, not a presentation-layer detail.
     */
    public static final String CROSS_RELATIONAL_SENTINEL = "cross_relational";

    // ─── Alias resolution ────────────────────────────────────────────────────────

    /**
     * Canonical short-alias → grammar-config-ID mappings.
     *
     * <p>Valid keys (case-insensitive): {@code adj_modifier}, {@code modifier},
     * {@code adj_predicate}, {@code predicate}, {@code subject_of}, {@code subject},
     * {@code object_of}, {@code object}. Any key not listed here is passed through
     * unchanged (assumed to already be a canonical grammar-config ID).
     *
     * <p>If new relations are added to the grammar config, register their aliases here.
     */
    private static final Map<String, String> RELATION_ALIASES = Map.of(
        "adj_modifier", "noun_modifiers",
        "modifier",     "noun_modifiers",
        "adj_predicate","noun_adj_predicates",
        "predicate",    "noun_adj_predicates",
        "subject_of",   "noun_verbs",
        "subject",      "noun_verbs",
        "object_of",    "verb_nouns",
        "object",       "verb_nouns"
    );

    /**
     * Resolves short relation aliases (e.g. "adj_predicate") to canonical grammar config IDs
     * (e.g. "noun_adj_predicates"). Pass-through for strings that are already canonical IDs.
     * Resolution is case-insensitive; the returned value is always lower-cased.
     *
     * @throws IllegalArgumentException if {@code relation} is {@code null}
     */
    public static String resolveRelationAlias(String relation) {
        if (relation == null) throw new IllegalArgumentException("relation must not be null");
        String lower = relation.toLowerCase();
        return RELATION_ALIASES.getOrDefault(lower, lower);
    }

    /**
     * Validates that every target relation ID in {@link #RELATION_ALIASES} exists in the
     * given grammar config. Logs a warning for any alias that points to an unknown ID.
     * Call this during server startup after the grammar config is loaded.
     * Validates that every entry in {@link #RELATION_ALIASES} maps to a relation ID that
     * actually exists in {@code config}. Throws {@link IllegalStateException} on the first
     * mismatch so that misconfigured deployments fail fast at server startup rather than
     * silently routing requests to a non-existent relation.
     *
     * @param config the loaded grammar configuration to validate against
     * @throws IllegalStateException if any alias references an unknown relation ID
     */
    public static void validateAliases(GrammarConfig config) {
        for (Map.Entry<String, String> entry : RELATION_ALIASES.entrySet()) {
            String configId = entry.getValue();
            if (config.relation(configId).isEmpty()) {
                throw new IllegalStateException(
                    "RELATION_ALIASES entry '" + entry.getKey() + "' → '" + configId
                    + "' references unknown relation ID '" + configId + "' in grammar config."
                    + " Fix the alias map or the grammar config before starting the server.");
            }
        }
    }

    /**
     * Finds the CQL reverse-lookup pattern for the best relation matching {@code posGroup}.
     * Searches for {@code primaryTypes} first; falls back to any relation with a known
     * {@code relationType} when no primary-type match is found.
     * Returns {@code fallback} when {@code grammarConfig} is null or no match is found.
     *
     * @param grammarConfig  the grammar configuration to search; returns {@code fallback} if null
     * @param posGroup       the collocate POS group to match against
     * @param fallback       default pattern to return when no relation matches
     * @param primaryTypes   preferred relation types to try first (in-order search)
     * @return the reverse-lookup CQL pattern, or {@code fallback}
     */
    public static String findBestCollocatePattern(
            @Nullable GrammarConfig grammarConfig,
            PosGroup posGroup,
            String fallback,
            RelationType... primaryTypes) {
        if (grammarConfig == null) return fallback;
        Set<RelationType> primary = Set.of(primaryTypes);
        return grammarConfig.relations().stream()
            .filter(r -> r.relationType().map(primary::contains).orElse(false)
                && r.collocatePosGroup() == posGroup)
            .findFirst()
            .map(r -> buildCollocateReversePattern(r))
            .orElseGet(() -> grammarConfig.relations().stream()
                .filter(r -> r.collocatePosGroup() == posGroup && r.relationType().isPresent())
                .findFirst()
                .map(r -> buildCollocateReversePattern(r))
                .orElse(fallback));
    }

    // ─── Pattern building ─────────────────────────────────────────────────────────

    /**
     * Builds the BCQL pattern with both headword and collocate lemmas substituted.
     *
     * @param config         the relation providing pattern, headPosition, and collocatePosition
     * @param headword       the lemma for the head position
     * @param collocateLemma the lemma for the collocate position
     * @return the fully-substituted BCQL pattern string
     */
    public static String buildFullPattern(RelationConfig config, @Nullable String headword, @Nullable String collocateLemma) {
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
    public static PosGroup computeCollocatePosGroup(@Nullable String pattern) {
        if (pattern == null) return PosGroup.OTHER;
        String pat = pattern.toLowerCase(Locale.ROOT);
        String target = extractLabelContent(pat, 2);
        if (target == null) target = pat;
        return resolvePosGroupFromPrefix(target, "xpos=");
    }

    /** Extracts the bracket content of the nth labeled position (e.g. "2:[...]"). */
    private static @Nullable String extractLabelContent(String pat, int label) {
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
        if (s.contains(q + "jj")) return PosGroup.ADJ;
        if (s.contains(q + "vb")) return PosGroup.VERB;
        if (s.contains(q + "nn")) return PosGroup.NOUN;
        if (s.contains(q + "rb")) return PosGroup.ADV;
        if (s.contains(q + "in")) return PosGroup.OTHER;
        if (s.contains(q + "rp") || s.contains(q + "to")) return PosGroup.OTHER;
        return PosGroup.OTHER;
    }
}

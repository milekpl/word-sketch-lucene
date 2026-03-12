package pl.marcinmilkowski.word_sketch.config;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pl.marcinmilkowski.word_sketch.model.PosGroup;

/**
 * Shared utilities for relation identifier handling.
 */
public final class RelationUtils {

    private static final Logger logger = LoggerFactory.getLogger(RelationUtils.class);

    private RelationUtils() {}

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
     *
     * @param config the loaded grammar configuration to validate against
     */
    public static void validateAliases(GrammarConfig config) {
        for (Map.Entry<String, String> entry : RELATION_ALIASES.entrySet()) {
            String configId = entry.getValue();
            if (config.relation(configId).isEmpty()) {
                logger.warn(
                    "RELATION_ALIASES entry '{}' → '{}' references unknown relation ID '{}' in grammar config",
                    entry.getKey(), configId, configId);
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
            .map(r -> RelationPatternUtils.buildCollocateReversePattern(r))
            .orElseGet(() -> grammarConfig.relations().stream()
                .filter(r -> r.collocatePosGroup() == posGroup && r.relationType().isPresent())
                .findFirst()
                .map(r -> RelationPatternUtils.buildCollocateReversePattern(r))
                .orElse(fallback));
    }
}

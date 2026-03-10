package pl.marcinmilkowski.word_sketch.api;

import java.util.Map;

/**
 * Shared utilities for relation identifier handling across API handlers.
 */
class RelationUtils {

    private RelationUtils() {}

    /** Canonical short-alias → grammar-config-ID mappings. */
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
     */
    static String resolveRelationAlias(String relation) {
        if (relation == null) return null;
        return RELATION_ALIASES.getOrDefault(relation, relation);
    }
}

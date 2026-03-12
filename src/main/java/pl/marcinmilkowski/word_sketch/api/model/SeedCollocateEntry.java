package pl.marcinmilkowski.word_sketch.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Typed record for a single seed-collocate entry in exploration endpoint responses.
 *
 * <p>The three fields mirror the JSON keys emitted by the previous
 * {@code Map<String,Object>}-based assembly: {@code word}, {@code log_dice},
 * and {@code frequency}. Jackson serialises the record directly via its
 * component accessors.</p>
 */
public record SeedCollocateEntry(
        String word,
        @JsonProperty("log_dice") double logDice,
        long frequency) {}

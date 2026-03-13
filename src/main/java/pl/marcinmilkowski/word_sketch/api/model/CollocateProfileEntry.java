package pl.marcinmilkowski.word_sketch.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Per-collocate entry in a comparison response, capturing sharing statistics
 * across the seed nouns.
 */
public record CollocateProfileEntry(
        String word,
        @JsonProperty("present_in") int presentIn,
        @JsonProperty("total_nouns") int totalNouns,
        @JsonProperty("avg_logdice") double avgLogDice,
        @JsonProperty("max_logdice") double maxLogDice,
        double variance,
        @JsonProperty("commonality_score") double commonalityScore,
        @JsonProperty("distinctiveness_score") double distinctivenessScore,
        String category,
        @JsonProperty("noun_scores") Map<String, Double> nounScores,
        @JsonProperty("specific_to") @JsonInclude(JsonInclude.Include.NON_NULL) String specificTo) {}

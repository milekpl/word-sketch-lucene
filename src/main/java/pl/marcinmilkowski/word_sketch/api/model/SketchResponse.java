package pl.marcinmilkowski.word_sketch.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Typed top-level response record for word sketch endpoints
 * ({@code GET /api/sketch/{lemma}} and {@code GET /api/sketch/{lemma}/{relation}}).
 *
 * <p>The {@code type} field is set to {@code "dependency"} for dependency-sketch responses and
 * absent for surface-sketch responses. The {@code warnings} field is present only when one or
 * more relations failed during execution ({@code status} is {@code "partial"} in that case).</p>
 *
 * <p>Replaces the raw {@code Map<String,Object>} previously assembled inline in
 * {@link pl.marcinmilkowski.word_sketch.api.SketchHandlers}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SketchResponse(
        String status,
        String lemma,
        /** Set to {@code "dependency"} for dep-sketch responses; absent for surface-sketch responses. */
        @Nullable String type,
        @JsonProperty("patterns") Map<String, RelationEntry> relations,
        /** Non-null (and status is {@code "partial"}) when at least one relation query failed. */
        @Nullable List<String> warnings) {}

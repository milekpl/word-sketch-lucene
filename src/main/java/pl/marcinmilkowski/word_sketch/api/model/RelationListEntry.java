package pl.marcinmilkowski.word_sketch.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * Typed response record for a single entry in the grammar-relations catalogue
 * ({@code GET /api/sketch/surface-relations} and {@code GET /api/sketch/dep-relations}).
 *
 * <p>The {@code deprel} field is present only for dependency-type relations; it is absent
 * ({@code null}) for surface relations and suppressed in JSON output via
 * {@link JsonInclude.Include#NON_NULL}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelationListEntry(
        String id,
        String name,
        @Nullable String description,
        @JsonProperty("relation_type") String relationType,
        @JsonProperty("head_pos_group") String headPosGroup,
        @JsonProperty("collocate_pos_group") String collocatePosGroup,
        String pattern,
        /** Present for dependency relations; absent for surface relations. */
        @Nullable String deprel) {}

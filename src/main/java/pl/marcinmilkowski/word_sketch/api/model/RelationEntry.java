package pl.marcinmilkowski.word_sketch.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Typed response record for a single grammatical-relation block in a word sketch response.
 *
 * <p>Covers both surface-pattern relations (where {@code description} and {@code deprel} are
 * absent) and dependency relations (where those fields are present). Fields annotated with
 * {@link JsonInclude.Include#NON_NULL} are omitted from JSON output when {@code null}, keeping
 * surface-relation responses clean.</p>
 *
 * <p>Replaces the raw {@code Map<String,Object>} previously built by
 * {@code SketchResponseAssembler.buildSurfaceRelationEntry} and
 * {@code SketchResponseAssembler.buildDepRelationEntry}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelationEntry(
        String id,
        String name,
        @JsonProperty("cql") String pattern,
        /** Present for dependency relations; absent ({@code null}) for surface relations. */
        @Nullable String description,
        @JsonProperty("relation_type") String relationType,
        @JsonProperty("collocate_pos_group") String collocatePosGroup,
        @JsonProperty("total_matches") long totalMatches,
        List<CollocateEntry> collocations,
        /** Present for dependency relations; absent ({@code null}) for surface relations. */
        @Nullable String deprel) {}

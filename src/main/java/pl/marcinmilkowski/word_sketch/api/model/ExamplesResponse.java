package pl.marcinmilkowski.word_sketch.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Typed response record for concordance-examples endpoints
 * ({@code GET /api/semantic-field/examples} and {@code GET /api/concordance/examples}).
 *
 * <p>Using a record instead of {@code Map<String,Object>} enforces the response shape at
 * compile time and makes the JSON contract explicit. Jackson 2.12+ serialises records
 * directly via their component accessors.</p>
 *
 * <p>The {@code fallback} component is {@code null} for normal responses and {@code true}
 * when the requested relation was not found and a proximity fallback pattern was used
 * (concordance endpoint only). {@link JsonInclude#NON_NULL} suppresses the field when absent
 * so that normal responses are unaffected.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExamplesResponse(
        String status,
        String seed,
        String collocate,
        String relation,
        String bcql,
        int top,
        @JsonProperty("total_results") int totalResults,
        @Nullable Boolean fallback,
        List<ExampleEntry> examples) {}

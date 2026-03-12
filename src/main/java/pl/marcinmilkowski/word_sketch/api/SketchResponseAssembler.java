package pl.marcinmilkowski.word_sketch.api;

import org.jspecify.annotations.NonNull;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.config.RelationType;
import pl.marcinmilkowski.word_sketch.model.QueryResults;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts model-layer sketch result objects into JSON-ready maps for API responses.
 *
 * <p>This class owns the model-to-presentation translation for word sketch endpoints so that
 * {@link SketchHandlers} stays focused on HTTP routing and parameter parsing. The pattern
 * mirrors {@link ExploreResponseAssembler} for exploration endpoints.</p>
 */
final class SketchResponseAssembler {

    private SketchResponseAssembler() {}

    /**
     * Builds the common surface-relation response map shared by the full-sketch and
     * single-relation handlers:
     * {@code {id, name, pattern, relation_type, collocate_pos_group, total_matches, collocations}}.
     */
    static @NonNull Map<String, Object> buildSurfaceRelationEntry(
            @NonNull RelationConfig rel,
            @NonNull List<Map<String, Object>> collocations,
            long totalMatches) {
        Map<String, Object> relData = new HashMap<>();
        relData.put("id", rel.id());
        relData.put("name", rel.name());
        relData.put("pattern", rel.pattern());
        relData.put("relation_type", RelationType.SURFACE.name());
        relData.put("collocate_pos_group", rel.collocatePosGroup().label());
        relData.put("total_matches", totalMatches);
        relData.put("collocations", collocations);
        return relData;
    }

    /**
     * Builds the response map for a single dependency relation entry.
     */
    static @NonNull Map<String, Object> buildDepRelationEntry(
            @NonNull RelationConfig rel,
            @NonNull List<QueryResults.WordSketchResult> results,
            @NonNull List<Map<String, Object>> collocations) {
        Map<String, Object> relData = new HashMap<>();
        relData.put("id", rel.id());
        relData.put("name", rel.name());
        relData.put("description", rel.description());
        relData.put("relation_type", RelationType.DEP.name());
        relData.put("deprel", rel.deriveDeprel());
        relData.put("pattern", rel.pattern());
        relData.put("collocate_pos_group", rel.collocatePosGroup().label());
        relData.put("total_matches", results.stream().mapToLong(QueryResults.WordSketchResult::frequency).sum());
        relData.put("collocations", collocations);
        return relData;
    }

    /** Formats a single {@link QueryResults.WordSketchResult} for inclusion in the collocations list. */
    static @NonNull Map<String, Object> formatWordSketchResult(QueryResults.WordSketchResult result) {
        Map<String, Object> word = new HashMap<>();
        word.put("lemma", result.lemma());
        word.put("frequency", result.frequency());
        word.put("log_dice", result.logDice());
        word.put("pos", result.pos());
        return word;
    }
}

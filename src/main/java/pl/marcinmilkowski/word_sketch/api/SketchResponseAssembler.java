package pl.marcinmilkowski.word_sketch.api;

import org.jspecify.annotations.NonNull;
import pl.marcinmilkowski.word_sketch.api.model.CollocateEntry;
import pl.marcinmilkowski.word_sketch.api.model.RelationEntry;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.config.RelationUtils;
import pl.marcinmilkowski.word_sketch.model.RelationType;
import pl.marcinmilkowski.word_sketch.model.sketch.*;

import java.util.List;

/**
 * Converts model-layer sketch result objects into typed response records for API responses.
 *
 * <p>This class owns the model-to-presentation translation for word sketch endpoints so that
 * {@link SketchHandlers} stays focused on HTTP routing and parameter parsing. The pattern
 * mirrors {@link ExploreResponseAssembler} for exploration endpoints.</p>
 */
final class SketchResponseAssembler {

    private SketchResponseAssembler() {}

    /**
     * Builds the typed surface-relation entry shared by the full-sketch and single-relation
     * handlers. Fields: {@code id, name, pattern, relation_type, collocate_pos_group,
     * total_matches, collocations}.
     */
    static @NonNull RelationEntry buildSurfaceRelationEntry(
            @NonNull RelationConfig rel,
            @NonNull List<CollocateEntry> collocations,
            long totalMatches) {
        return new RelationEntry(
                rel.id(),
                rel.name(),
                rel.pattern(),
                null,
                RelationType.SURFACE.label(),
            RelationUtils.computeHeadPosGroup(rel).label(),
                rel.collocatePosGroup().label(),
                totalMatches,
                collocations,
                null);
    }

    /**
     * Builds the typed entry for a single dependency relation.
     */
    static @NonNull RelationEntry buildDepRelationEntry(
            @NonNull RelationConfig rel,
            @NonNull List<WordSketchResult> results,
            @NonNull List<CollocateEntry> collocations) {
        return new RelationEntry(
                rel.id(),
                rel.name(),
                rel.pattern(),
                rel.description(),
                RelationType.DEP.label(),
            RelationUtils.computeHeadPosGroup(rel).label(),
                rel.collocatePosGroup().label(),
                results.stream().mapToLong(WordSketchResult::frequency).sum(),
                collocations,
                rel.deriveDeprel());
    }

    /** Converts a {@link WordSketchResult} into a typed {@link CollocateEntry}. */
    static @NonNull CollocateEntry toCollocateEntry(@NonNull WordSketchResult result) {
        return new CollocateEntry(result.lemma(), result.frequency(), result.logDice(), result.pos());
    }
}

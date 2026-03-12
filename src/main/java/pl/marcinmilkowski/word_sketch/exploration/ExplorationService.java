package pl.marcinmilkowski.word_sketch.exploration;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.model.exploration.FetchExamplesOptions;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.model.exploration.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.exploration.SingleSeedExplorationOptions;

/**
 * Public API for semantic field exploration operations.
 *
 * <p>This interface bundles three related exploration modes — single-seed, multi-seed, and
 * collocate profile comparison — because all three share the same underlying corpus-query
 * infrastructure and are presented as one coherent feature surface to the HTTP layer.
 * Keeping them together avoids scattering a single conceptual service across multiple
 * interfaces while there is only one implementation.</p>
 *
 * <p>{@link SemanticFieldExplorer} is the canonical implementation.  It delegates raw
 * corpus lookups to a {@link pl.marcinmilkowski.word_sketch.query.QueryExecutor}, which
 * supplies the actual index access; the explorer layer owns only the higher-level
 * aggregation and scoring logic.</p>
 */
public interface ExplorationService {

    /**
     * Explore semantic field around a single seed word using the given grammatical relation.
     */
    @NonNull ExplorationResult exploreByPattern(
            @NonNull String seed,
            @NonNull RelationConfig relationConfig,
            @NonNull SingleSeedExplorationOptions opts) throws IOException;

    /**
     * Multi-seed semantic field exploration: finds collocates shared across multiple seeds.
     */
    @NonNull ExplorationResult exploreMultiSeed(
            @NonNull Set<String> seeds,
            @NonNull RelationConfig relationConfig,
            @NonNull ExplorationOptions opts) throws IOException;

    /**
     * Compares collocate profiles across a set of seed nouns, revealing shared and distinctive collocates.
     */
    @NonNull ComparisonResult compareCollocateProfiles(
            @NonNull Set<String> seedNouns,
            @NonNull ExplorationOptions opts) throws IOException;

    /**
     * Fetch example concordance results for a collocate-headword pair using the provided relation pattern.
     */
    @NonNull List<QueryResults.CollocateResult> fetchExamples(
            @NonNull String collocate,
            @NonNull String headword,
            @NonNull RelationConfig relationConfig,
            @NonNull FetchExamplesOptions opts) throws IOException;
}

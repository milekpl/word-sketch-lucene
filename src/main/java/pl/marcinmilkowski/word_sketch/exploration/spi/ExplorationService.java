package pl.marcinmilkowski.word_sketch.exploration.spi;

import java.util.Set;

import org.jspecify.annotations.NonNull;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.model.exploration.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.exploration.FetchExamplesOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.FetchExamplesResult;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.SingleSeedExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult;

/**
 * Public API for semantic field exploration operations.
 *
 * <p>This interface bundles three related exploration modes — single-seed, multi-seed, and
 * collocate profile comparison — because all three share the same underlying corpus-query
 * infrastructure and are presented as one coherent feature surface to the HTTP layer.
 * Keeping them together avoids scattering a single conceptual service across multiple
 * interfaces while there is only one implementation.</p>
 *
 * <p>{@link pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer} is the canonical
 * implementation.  It delegates raw corpus lookups to a
 * {@link pl.marcinmilkowski.word_sketch.query.QueryExecutor}, which supplies the actual index
 * access; the explorer layer owns only the higher-level aggregation and scoring logic.</p>
 */
public interface ExplorationService {

    /**
     * Explore semantic field around a single seed word using the given grammatical relation.
     *
     * @throws IllegalArgumentException if {@code seed} is blank or {@code opts} are invalid
     * @throws pl.marcinmilkowski.word_sketch.exploration.ExplorationException if corpus access fails
     */
    @NonNull ExplorationResult exploreByRelation(
            @NonNull String seed,
            @NonNull RelationConfig relationConfig,
            @NonNull SingleSeedExplorationOptions opts);

    /**
     * Multi-seed semantic field exploration: finds collocates shared across multiple seeds.
     *
     * @throws IllegalArgumentException if {@code seeds} is empty or fewer than 2 seeds are provided
     * @throws pl.marcinmilkowski.word_sketch.exploration.ExplorationException if corpus access fails
     */
    @NonNull ExplorationResult exploreMultiSeed(
            @NonNull Set<String> seeds,
            @NonNull RelationConfig relationConfig,
            @NonNull ExplorationOptions opts);

    /**
     * Compares collocate profiles across a set of seed nouns, revealing shared and distinctive collocates.
     *
     * @throws IllegalArgumentException if {@code seeds} is empty or fewer than 2 seeds are provided
     * @throws pl.marcinmilkowski.word_sketch.exploration.ExplorationException if corpus access fails
     */
    @NonNull ComparisonResult compareCollocateProfiles(
            @NonNull Set<String> seeds,
            @NonNull RelationConfig relationConfig,
            @NonNull ExplorationOptions opts);

    /**
     * Fetch example concordance results for a seed-collocate pair using the provided relation pattern.
     *
     * @throws IllegalArgumentException if {@code seed} or {@code collocate} is blank
     * @throws pl.marcinmilkowski.word_sketch.exploration.ExplorationException if corpus access fails
     */
    @NonNull FetchExamplesResult fetchExamples(
            @NonNull String seed,
            @NonNull String collocate,
            @NonNull RelationConfig relationConfig,
            @NonNull FetchExamplesOptions opts);
}

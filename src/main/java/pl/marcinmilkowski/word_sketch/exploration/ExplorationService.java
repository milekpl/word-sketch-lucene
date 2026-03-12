package pl.marcinmilkowski.word_sketch.exploration;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.model.FetchExamplesOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.exploration.SingleSeedExplorationOptions;

/**
 * Public API for semantic field exploration operations.
 * Implemented by {@link SemanticFieldExplorer}.
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
     * Fetch example sentences for a collocate-headword pair using the provided relation pattern.
     */
    @NonNull List<String> fetchExamples(
            @NonNull String collocate,
            @NonNull String headword,
            @NonNull RelationConfig relationConfig,
            @NonNull FetchExamplesOptions opts) throws IOException;
}

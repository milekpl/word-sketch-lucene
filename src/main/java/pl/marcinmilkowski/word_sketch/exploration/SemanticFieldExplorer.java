package pl.marcinmilkowski.word_sketch.exploration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.exploration.spi.ExplorationException;
import pl.marcinmilkowski.word_sketch.exploration.spi.ExplorationService;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.config.RelationPatternUtils;
import pl.marcinmilkowski.word_sketch.config.RelationUtils;
import pl.marcinmilkowski.word_sketch.model.exploration.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.SingleSeedExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.exploration.FetchExamplesOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.FetchExamplesResult;
import pl.marcinmilkowski.word_sketch.model.PosGroup;
import pl.marcinmilkowski.word_sketch.model.sketch.*;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

/**
 * Analyses semantic fields around seed words by querying collocate patterns.
 * Supports single-seed exploration and multi-seed comparison to find
 * common and distinctive collocates across seeds.
 *
 * <h2>Exploration Mode (single seed)</h2>
 * <ol>
 *   <li>Find adjectives that modify the seed noun</li>
 *   <li>For each top adjective, find OTHER nouns it modifies</li>
 *   <li>Score nouns by how many seed adjectives they share</li>
 *   <li>Build a semantic class of related nouns</li>
 * </ol>
 *
 * <h2>Comparison Mode (multi-seed)</h2>
 * <p>Given multiple seeds, compares collocate profiles to reveal shared (semantic core)
 * and distinctive collocates. See {@link CollocateProfileComparator#compareCollocateProfiles}.</p>
 *
 * <p>Thin facade wiring {@link SingleSeedExplorer}, {@link MultiSeedExplorer}, and
 * {@link CollocateProfileComparator} for the HTTP exploration layer.</p>
 */
public class SemanticFieldExplorer implements ExplorationService {

    private static final Logger logger = LoggerFactory.getLogger(SemanticFieldExplorer.class);

    private static final String FALLBACK_NOUN_PATTERN = "[xpos=\"NN.*\"]";

    private final QueryExecutor executor;
    private final CollocateProfileComparator comparator;
    private final MultiSeedExplorer multiSeedExplorer;
    private final SingleSeedExplorer singleSeedExplorer;

    public SemanticFieldExplorer(@NonNull QueryExecutor executor, @NonNull GrammarConfig grammarConfig) {
        this(executor,
             new CollocateProfileComparator(executor, grammarConfig),
             new MultiSeedExplorer(executor),
             grammarConfig);
    }

    /**
     * Package-private test constructor. Accepts a pre-resolved noun CQL pattern directly,
     * bypassing grammar config loading. Use in unit tests where a real grammar is unavailable.
     */
    SemanticFieldExplorer(QueryExecutor executor, String nounCqlPattern) {
        this.executor = executor;
        this.comparator = new CollocateProfileComparator(executor, null);
        this.multiSeedExplorer = new MultiSeedExplorer(executor);
        this.singleSeedExplorer = new SingleSeedExplorer(executor, nounCqlPattern);
    }

    /**
     * Primary constructor that accepts all dependencies explicitly, enabling injection.
     * Use this in production wiring code (e.g. {@code Main}) to supply pre-constructed
     * {@link CollocateProfileComparator} and {@link MultiSeedExplorer} instances.
     *
     * @param executor          the query executor for corpus lookups
     * @param comparator        collocate profile comparator (multi-seed comparison)
     * @param multiSeedExplorer multi-seed intersection explorer
     * @param grammarConfig     grammar config used to derive the noun CQL constraint;
     *                          must be non-null — use the package-private test constructor
     *                          to bypass grammar config in unit tests.
     */
    public SemanticFieldExplorer(
            @NonNull QueryExecutor executor,
            @NonNull CollocateProfileComparator comparator,
            @NonNull MultiSeedExplorer multiSeedExplorer,
            @NonNull GrammarConfig grammarConfig) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.comparator = Objects.requireNonNull(comparator, "comparator must not be null");
        this.multiSeedExplorer = Objects.requireNonNull(multiSeedExplorer, "multiSeedExplorer must not be null");
        String nounCqlPattern = RelationUtils.findBestCollocatePattern(
            Objects.requireNonNull(grammarConfig, "grammarConfig must not be null"),
            PosGroup.NOUN, FALLBACK_NOUN_PATTERN);
        this.singleSeedExplorer = new SingleSeedExplorer(executor, nounCqlPattern);
    }

    // ==================== EXPLORATION MODE ====================

    /**
     * Convenience overload that extracts pattern strings from a {@link RelationConfig},
     * mirroring the delegation style used by the multi-seed path.
     */
    public @NonNull ExplorationResult exploreByRelation(
            @NonNull String seed,
            @NonNull RelationConfig relationConfig,
            @NonNull SingleSeedExplorationOptions opts) throws ExplorationException {
        relationConfig.validate();
        if (relationConfig.collocatePosGroup() == PosGroup.OTHER) {
            throw new IllegalArgumentException(
                "Relation '" + relationConfig.id() + "' has unsupported collocate POS group (OTHER)" +
                " — cannot perform reverse pattern lookup");
        }
        try {
            return singleSeedExplorer.explore(
                seed,
                relationConfig.name(),
                RelationPatternUtils.buildFullPattern(relationConfig, seed),
                RelationPatternUtils.buildCollocateReversePattern(relationConfig),
                opts);
        } catch (java.io.IOException e) {
            throw new ExplorationException("Failed to explore relation '" + relationConfig.id() +
                "' for seed '" + seed + "'", e);
        }
    }


    // ==================== COMPARISON MODE ====================

    /**
     * Compares adjective collocate profiles across a set of seed nouns, revealing which
     * adjectives are shared across seeds (semantic core) and which are distinctive to
     * individual seeds.
     *
     * <p>Delegates computation to {@link CollocateProfileComparator}, which validates that
     * at least 2 seed nouns are provided.</p>
     *
     * @param seeds       Nouns to compare (e.g., "theory", "model", "hypothesis"); must not be null or empty
     * @param opts        exploration options; {@code topCollocates} and {@code minLogDice} are used
     * @return ComparisonResult with graded adjective profiles
     */
    public @NonNull ComparisonResult compareCollocateProfiles(
            @NonNull Set<String> seeds, @NonNull ExplorationOptions opts) throws ExplorationException {
        try {
            return comparator.compareCollocateProfiles(seeds, opts);
        } catch (java.io.IOException e) {
            throw new ExplorationException("Failed to compare collocate profiles for seeds " + seeds, e);
        }
    }

    /**
     * Fetch example sentences for a seed-collocate pair using the provided relation pattern.
     *
     * @param seed           the seed lemma
     * @param collocate      the collocate lemma
     * @param relationConfig the relation config defining the co-occurrence pattern
     * @param maxExamples    maximum number of deduplicated example sentences to return
     * @return {@link FetchExamplesResult} with concordance lines and the query used
     */
    public @NonNull FetchExamplesResult fetchExamples(@NonNull String seed, @NonNull String collocate,
            @NonNull RelationConfig relationConfig, @NonNull FetchExamplesOptions opts)
            throws ExplorationException {
        relationConfig.validate();
        int maxExamples = opts.maxExamples();
        String bcqlQuery = RelationPatternUtils.buildFullPattern(relationConfig, seed.toLowerCase(), collocate.toLowerCase());

        List<CollocateResult> results;
        try {
            results = executor.executeBcqlQuery(bcqlQuery, maxExamples);
        } catch (java.io.IOException e) {
            throw new ExplorationException("Failed to fetch examples for seed '" + seed +
                "' / collocate '" + collocate + "'", e);
        }

        Set<String> seen = new HashSet<>();
        List<CollocateResult> deduped = new ArrayList<>();
        for (CollocateResult r : results) {
            String s = r.sentence();
            if (s == null || s.isEmpty()) continue;
            if (seen.add(s)) {
                deduped.add(r);
                if (deduped.size() == maxExamples) break;
            }
        }
        return new FetchExamplesResult(deduped, bcqlQuery);
    }

    /**
     * Multi-seed semantic field exploration: finds collocates shared across multiple seeds.
     * Delegates to {@link MultiSeedExplorer} which owns the multi-seed algorithm.
     *
     * @param seeds          ordered seed words (at least 2)
     * @param relationConfig grammar relation to use for collocate lookup
     * @param opts           tuning parameters (topCollocates, minLogDice, minShared)
     * @return ExplorationResult mapping multi-seed data into the shared exploration model
     */
    public @NonNull ExplorationResult exploreMultiSeed(
            @NonNull Set<String> seeds,
            @NonNull RelationConfig relationConfig,
            @NonNull ExplorationOptions opts) throws ExplorationException {
        Objects.requireNonNull(seeds, "seeds must not be null");
        if (seeds.size() < 2) {
            throw new IllegalArgumentException("At least 2 seeds are required for multi-seed exploration");
        }
        relationConfig.validate();
        try {
            return multiSeedExplorer.findCollocateIntersection(seeds, relationConfig, opts);
        } catch (java.io.IOException e) {
            throw new ExplorationException("Failed to explore multi-seed for relation '" +
                relationConfig.id() + "' with seeds " + seeds, e);
        }
    }

}
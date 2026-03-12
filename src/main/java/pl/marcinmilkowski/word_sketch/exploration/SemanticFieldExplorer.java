package pl.marcinmilkowski.word_sketch.exploration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.config.RelationPatternUtils;
import pl.marcinmilkowski.word_sketch.config.RelationUtils;
import pl.marcinmilkowski.word_sketch.model.exploration.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.SingleSeedExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.exploration.FetchExamplesResult;
import pl.marcinmilkowski.word_sketch.model.PosGroup;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

/**
 * Analyses semantic fields around seed words by querying collocate patterns.
 * Supports both single-seed exploration and multi-seed comparison to find
 * common and distinctive collocates across seeds.
 *
 * <h2>Exploration Mode (single seed)</h2>
 * <p>Starting from a seed word, discovers semantically related words by:</p>
 * <ol>
 *   <li>Find adjectives that modify the seed noun</li>
 *   <li>For each top adjective, find OTHER nouns it modifies</li>
 *   <li>Score nouns by how many seed adjectives they share</li>
 *   <li>Build a semantic class of related nouns</li>
 * </ol>
 *
 * <h2>Example: Exploring from "house"</h2>
 * <pre>
 * Seed: house
 * Top adjectives: beautiful, old, big, small, private, new...
 *
 * "beautiful" modifies: beach, garden, view, apartment, landscape, house...
 * "old" modifies: town, city, friend, man, house, building...
 * "big" modifies: deal, city, house, hotel, family...
 *
 * Nouns sharing 3+ adjectives with "house":
 *   apartment (beautiful, old, big, small, new) - score: 5
 *   building (old, big, new) - score: 3
 *   hotel (beautiful, big, small, new) - score: 4
 *
 * => Semantic class: dwellings/places
 * => Core adjectives: beautiful, old, big, small, new (shared by class)
 * </pre>
 *
 * <h2>Comparison Mode (multi-seed)</h2>
 * <p>Given multiple seed words, compares their collocate profiles to reveal
 * which collocates are shared across all seeds (the semantic core) and which
 * are distinctive to individual seeds. See {@link CollocateProfileComparator#compareCollocateProfiles}.</p>
 *
 * <h2>Result classes</h2>
 * <p>Result DTOs ({@code ExplorationResult}, {@code DiscoveredNoun},
 * {@code CoreCollocate}, {@code ComparisonResult}, {@code CollocateProfile}, {@code Edge})
 * live in the {@code pl.marcinmilkowski.word_sketch.model} package.</p>
 *
 * <h2>Design: pure coordination facade for the HTTP exploration layer</h2>
 * <p>This class is a thin facade that wires and delegates to three algorithm classes:</p>
 * <ul>
 *   <li>{@link SingleSeedExplorer} — single-seed exploration algorithm (see {@link #exploreByPattern})</li>
 *   <li>{@link MultiSeedExplorer} — multi-seed intersection algorithm (see {@link #exploreMultiSeed})</li>
 *   <li>{@link CollocateProfileComparator} — cross-relational profile comparison (see {@link #compareCollocateProfiles})</li>
 * </ul>
 * <p>The two pass-through methods ({@link #exploreMultiSeed} and {@link #compareCollocateProfiles})
 * exist here rather than being inlined in the HTTP handlers because all three exploration modes
 * share the same corpus-query infrastructure and are presented as one coherent feature surface
 * to the HTTP layer. This facade insulates the HTTP handlers from changes to individual algorithm
 * classes and from the wiring logic that connects them.</p>
 *
 * <h2>Computational Limits</h2>
 * <p>Uses logDice thresholds at each step to prevent combinatorial explosion:</p>
 * <ul>
 *   <li>Top N adjectives from seed (default: 20)</li>
 *   <li>Top M nouns per adjective (default: 30)</li>
 *   <li>Minimum overlap threshold (default: 2 shared adjectives)</li>
 * </ul>
 */
public class SemanticFieldExplorer implements ExplorationService {

    private static final Logger logger = LoggerFactory.getLogger(SemanticFieldExplorer.class);

    private static final String FALLBACK_NOUN_PATTERN = "[xpos=\"NN.*\"]";

    private final QueryExecutor executor;
    private final CollocateProfileComparator comparator;
    private final MultiSeedExplorer multiSeedExplorer;
    private final SingleSeedExplorer singleSeedExplorer;

    public SemanticFieldExplorer(QueryExecutor executor, GrammarConfig grammarConfig) {
        this(executor,
             new CollocateProfileComparator(executor, grammarConfig),
             new MultiSeedExplorer(executor),
             grammarConfig);
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
     *                          {@code null} falls back to {@link #FALLBACK_NOUN_PATTERN}
     */
    public SemanticFieldExplorer(
            QueryExecutor executor,
            CollocateProfileComparator comparator,
            MultiSeedExplorer multiSeedExplorer,
            GrammarConfig grammarConfig) {
        this.executor = executor;
        this.comparator = comparator;
        this.multiSeedExplorer = multiSeedExplorer;
        String nounCqlConstraint = RelationUtils.findBestCollocatePattern(
            grammarConfig, PosGroup.NOUN, FALLBACK_NOUN_PATTERN);
        this.singleSeedExplorer = new SingleSeedExplorer(executor, nounCqlConstraint);
    }

    // ==================== EXPLORATION MODE ====================

    /**
     * Convenience overload that extracts pattern strings from a {@link RelationConfig},
     * mirroring the delegation style used by the multi-seed path.
     */
    public @NonNull ExplorationResult exploreByPattern(
            @NonNull String seed,
            @NonNull RelationConfig relationConfig,
            @NonNull SingleSeedExplorationOptions opts) throws IOException {
        if (relationConfig.relationType().isEmpty()) {
            throw new IllegalArgumentException(
                "Relation '" + relationConfig.id() + "' has no relation_type — cannot perform exploration");
        }
        if (relationConfig.collocatePosGroup() == PosGroup.OTHER) {
            throw new IllegalArgumentException(
                "Relation '" + relationConfig.id() + "' has unsupported collocate POS group (OTHER)" +
                " — cannot perform reverse pattern lookup");
        }
        return singleSeedExplorer.explore(
            seed,
            relationConfig.name(),
            RelationPatternUtils.buildFullPattern(relationConfig, seed),
            RelationPatternUtils.buildCollocateReversePattern(relationConfig),
            opts);
    }

    /**
     * Explore semantic field using pre-resolved BCQL pattern strings.
     *
     * <p><strong>Package-private testing seam.</strong> Production code should always call
     * {@link #exploreByPattern(String, RelationConfig, SingleSeedExplorationOptions)}, which
     * extracts the pattern strings from a {@link RelationConfig} and preserves the config
     * abstraction. This overload exists solely to enable unit tests that exercise exploration
     * logic with programmatically constructed patterns without requiring a full grammar config.
     *
     * @param seed          the seed noun to explore from
     * @param relationName  human-readable relation name for logging
     * @param bcqlPattern   BCQL pattern with headword already substituted
     * @param simplePattern simple reverse-lookup pattern (e.g., {@code [xpos="JJ.*"]})
     * @param opts          all tuning parameters including {@code reverseExpansionLimit}
     * @return ExplorationResult with discovered semantic class
     */
    ExplorationResult exploreByPattern(
            String seed,
            String relationName,
            String bcqlPattern,
            String simplePattern,
            SingleSeedExplorationOptions opts) throws IOException {
        return singleSeedExplorer.explore(seed, relationName, bcqlPattern, simplePattern, opts);
    }

    // ==================== COMPARISON MODE ====================

    /**
     * Compares adjective collocate profiles across a set of seed nouns, revealing which
     * adjectives are shared across seeds (semantic core) and which are distinctive to
     * individual seeds.
     *
     * <p>Policy: requires at least 2 non-blank seed nouns so the comparison is meaningful.
     * Delegates computation to {@link CollocateProfileComparator}.</p>
     *
     * @param seeds       Nouns to compare (e.g., "theory", "model", "hypothesis"); must not be null or empty
     * @param opts        exploration options; {@code topCollocates} and {@code minLogDice} are used
     * @return ComparisonResult with graded adjective profiles
     */
    public @NonNull ComparisonResult compareCollocateProfiles(
            @NonNull Set<String> seeds, @NonNull ExplorationOptions opts) throws IOException {
        if (seeds.size() < 2) {
            throw new IllegalArgumentException(
                "Profile comparison requires at least 2 seed nouns; received " + seeds.size());
        }
        return comparator.compareCollocateProfiles(seeds, opts);
    }

    /**
     * Fetch example sentences for a seed-collocate pair using the provided relation pattern.
     *
     * <h3>Query strategy: executeBcqlQuery (not executeSurfacePattern or executeCollocations)</h3>
     *
     * <p>This method retrieves <em>concordance examples</em> — full sentences showing a specific
     * (seed, collocate) pair in context — rather than a ranked list of collocates. It therefore
     * uses {@code executeBcqlQuery}, which returns {@link pl.marcinmilkowski.word_sketch.model.QueryResults.CollocateResult}
     * objects carrying the sentence text and character offsets needed for display.</p>
     *
     * <p>Neither {@code executeSurfacePattern} nor {@code executeCollocations} is appropriate
     * here because both return {@link pl.marcinmilkowski.word_sketch.model.QueryResults.WordSketchResult}
     * objects designed for ranked collocate scoring, not for sentence retrieval. The BCQL pattern
     * built by {@link pl.marcinmilkowski.word_sketch.config.RelationPatternUtils#buildFullPattern}
     * pins both the seed and the collocate at specific labeled positions (e.g. position 1 = seed,
     * position 2 = collocate), so the query is a precision lookup rather than a collocation scan.</p>
     *
     * <p><b>Important:</b> do not "unify" this with the {@link #fetchSeedCollocates} strategy.
     * The two serve different purposes: {@code fetchSeedCollocates} scans for <em>candidate</em>
     * collocates of an unknown-collocate query, while {@code fetchExamples} retrieves sentences
     * for an already-known (seed, collocate) pair. Using {@code executeSurfacePattern} here would
     * return scored collocate lists, not sentence examples.</p>
     *
     * @param seed           The seed lemma (e.g. a noun)
     * @param collocate      The collocate lemma (e.g. an adjective)
     * @param relationConfig The relation config defining how collocate and seed co-occur
     * @param maxExamples    Maximum number of deduplicated example sentences to return
     * @return {@link FetchExamplesResult} containing example concordance lines and the query used
     */
    public @NonNull FetchExamplesResult fetchExamples(@NonNull String seed, @NonNull String collocate,
            @NonNull RelationConfig relationConfig, int maxExamples)
            throws IOException {
        String bcqlQuery = RelationPatternUtils.buildFullPattern(relationConfig, seed.toLowerCase(), collocate.toLowerCase());

        List<QueryResults.CollocateResult> results = executor.executeBcqlQuery(bcqlQuery, maxExamples);

        Set<String> seen = new HashSet<>();
        List<QueryResults.CollocateResult> deduped = new ArrayList<>();
        for (QueryResults.CollocateResult r : results) {
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
            @NonNull ExplorationOptions opts) throws IOException {
        if (seeds.size() < 2) {
            throw new IllegalArgumentException(
                "Multi-seed exploration requires at least 2 seeds; received " + seeds.size());
        }
        return multiSeedExplorer.findCollocateIntersection(seeds, relationConfig, opts);
    }

}
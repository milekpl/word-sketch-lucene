package pl.marcinmilkowski.word_sketch.exploration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.config.RelationPatternBuilder;
import pl.marcinmilkowski.word_sketch.config.RelationUtils;
import pl.marcinmilkowski.word_sketch.model.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.model.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.ExplorationResult;
import pl.marcinmilkowski.word_sketch.model.FetchExamplesOptions;
import pl.marcinmilkowski.word_sketch.model.PosGroup;
import pl.marcinmilkowski.word_sketch.model.QueryResults;
import pl.marcinmilkowski.word_sketch.model.RelationType;
import pl.marcinmilkowski.word_sketch.model.SingleSeedExplorationOptions;
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
 * {@code CoreCollocate}, {@code ComparisonResult}, {@code AdjectiveProfile}, {@code Edge})
 * live in the {@code pl.marcinmilkowski.word_sketch.model} package.</p>
 *
 * <h2>Design: coordination facade for the HTTP exploration layer</h2>
 * <p>This class serves as the stable API surface for the HTTP exploration handlers:
 * it owns the single-seed exploration algorithm directly (see {@link #exploreByPattern}), and acts
 * as a coordination facade for the remaining operations — delegating multi-seed exploration to
 * {@link MultiSeedExplorer} and profile comparison to {@link CollocateProfileComparator}.
 * Keeping this boundary stable insulates the HTTP layer from changes to the individual algorithm
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
public class SemanticFieldExplorer {

    private static final Logger logger = LoggerFactory.getLogger(SemanticFieldExplorer.class);

    private static final String FALLBACK_NOUN_CQL_CONSTRAINT = "[xpos=\"NN.*\"]";

    private final QueryExecutor executor;
    private final CollocateProfileComparator comparator;
    private final MultiSeedExplorer multiSeedExplorer;
    private final String nounCqlConstraint;

    public SemanticFieldExplorer(QueryExecutor executor, GrammarConfig grammarConfig) {
        this.executor = executor;
        this.nounCqlConstraint = deriveNounCqlConstraint(grammarConfig);
        this.comparator = new CollocateProfileComparator(executor, grammarConfig);
        this.multiSeedExplorer = new MultiSeedExplorer(executor);
    }

    private static String deriveNounCqlConstraint(GrammarConfig grammarConfig) {
        return RelationUtils.findBestCollocatePattern(
            grammarConfig, PosGroup.NOUN, FALLBACK_NOUN_CQL_CONSTRAINT,
            RelationType.OBJECT_OF, RelationType.SUBJECT_OF);
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
        return exploreByPattern(
            seed,
            relationConfig.name(),
            RelationPatternBuilder.buildFullPattern(relationConfig, seed),
            RelationPatternBuilder.buildCollocateReversePattern(relationConfig),
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
     * @param opts          tuning parameters (topCollocates, nounsPerSeed, minLogDice, minShared)
     * @return ExplorationResult with discovered semantic class
     */
    ExplorationResult exploreByPattern(
            String seed,
            String relationName,
            String bcqlPattern,
            String simplePattern,
            SingleSeedExplorationOptions opts) throws IOException {

        int topPredicates = opts.base().topCollocates();
        int nounsPerPredicate = opts.nounsPerCollocate();
        double minLogDice = opts.base().minLogDice();
        int minShared = opts.base().minShared();

        if (seed == null || seed.isEmpty()) {
            throw new IllegalArgumentException("seed must not be blank");
        }

        String normalizedSeed = seed.toLowerCase().trim();

        logger.debug("Exploring semantic field: seed='{}', relation='{}', top={}, minShared={}, minLogDice={}", normalizedSeed, relationName, topPredicates, minShared, minLogDice);

        List<QueryResults.WordSketchResult> seedRelations = fetchSeedCollocates(
            normalizedSeed, bcqlPattern, simplePattern, minLogDice, topPredicates);

        if (seedRelations.isEmpty()) {
            logger.debug("No collocates found for seed '{}' — seed may be too rare", normalizedSeed);
            return ExplorationResult.empty(normalizedSeed);
        }


        // Build map: collocate -> logDice with seed
        Map<String, Double> seedCollocScores = new LinkedHashMap<>();
        Map<String, Long> seedCollocFrequencies = new LinkedHashMap<>();
        for (QueryResults.WordSketchResult r : seedRelations) {
            seedCollocScores.put(r.lemma().toLowerCase(), r.logDice());
            seedCollocFrequencies.put(r.lemma().toLowerCase(), r.frequency());
        }

        Map<String, Map<String, Double>> nounProfiles = buildCollocateToNounsMap(
            seedCollocScores, normalizedSeed, minLogDice, nounsPerPredicate, nounCqlConstraint);

        List<DiscoveredNoun> discoveredNouns = filterByMinShared(nounProfiles, minShared);
        discoveredNouns.sort((a, b) -> Double.compare(b.combinedRelevanceScore(), a.combinedRelevanceScore()));

        List<CoreCollocate> coreCollocates = identifyCoreCollocates(seedCollocScores, discoveredNouns);

        logger.debug("Exploration complete for '{}': {} nouns discovered, {} core collocates", normalizedSeed, discoveredNouns.size(), coreCollocates.size());

        return new ExplorationResult(List.of(normalizedSeed), seedCollocScores, seedCollocFrequencies, discoveredNouns, coreCollocates);
    }

    // ==================== EXPLORATION PHASE HELPERS ====================

    /**
     * Phase 1: Fetch seed collocates using the BCQL pattern, with fallback to simplePattern.
     */
    private List<QueryResults.WordSketchResult> fetchSeedCollocates(
            String seed, String bcqlPattern, String simplePattern,
            double minLogDice, int topPredicates) throws IOException {
        List<QueryResults.WordSketchResult> results = executor.executeSurfacePattern(
            bcqlPattern, minLogDice, topPredicates);
        if (results.isEmpty()) {
            logger.debug("  No results found for seed word. Trying fallback to simple pattern...");
            results = executor.executeCollocations(seed, simplePattern, minLogDice, topPredicates);
        }
        return results;
    }

    /**
     * Phase 2: For each collocate, find nouns it collocates with (reverse lookup).
     * Returns a map of noun → {collocate → logDice}.
     */
    private Map<String, Map<String, Double>> buildCollocateToNounsMap(
            Map<String, Double> seedCollocScores, String seed,
            double minLogDice, int nounsPerPredicate, String nounConstraint) throws IOException {
        Map<String, Map<String, Double>> nounProfiles = new LinkedHashMap<>();
        for (String collocate : seedCollocScores.keySet()) {
            List<QueryResults.WordSketchResult> nouns = executor.executeCollocations(
                collocate, nounConstraint, minLogDice, nounsPerPredicate);
            for (QueryResults.WordSketchResult r : nouns) {
                String noun = r.lemma().toLowerCase();
                if (noun.equals(seed)) continue;
                nounProfiles.computeIfAbsent(noun, k -> new LinkedHashMap<>())
                    .put(collocate, r.logDice());
            }
        }
        return nounProfiles;
    }

    /**
     * Phase 3: Score candidate nouns by how many shared collocates they have.
     * Nouns below {@code minShared} are filtered out.
     */
    private List<DiscoveredNoun> filterByMinShared(
            Map<String, Map<String, Double>> nounProfiles, int minShared) {
        List<DiscoveredNoun> discoveredNouns = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> entry : nounProfiles.entrySet()) {
            String noun = entry.getKey();
            Map<String, Double> collocScores = entry.getValue();
            int sharedCount = collocScores.size();
            if (sharedCount < minShared) continue;
            double combinedRelevanceScore = collocScores.values().stream().mapToDouble(Double::doubleValue).sum();
            double avgLogDice = combinedRelevanceScore / sharedCount;
            discoveredNouns.add(new DiscoveredNoun(
                noun, collocScores, sharedCount, combinedRelevanceScore, avgLogDice));
        }
        return discoveredNouns;
    }

    /**
     * Phase 4: Identify core collocates — those shared by enough discovered nouns.
     */
    private List<CoreCollocate> identifyCoreCollocates(
            Map<String, Double> seedCollocScores, List<DiscoveredNoun> discoveredNouns) {
        Map<String, Integer> collocFrequency = new LinkedHashMap<>();
        Map<String, Double> collocTotalScore = new LinkedHashMap<>();
        for (DiscoveredNoun dn : discoveredNouns) {
            for (Map.Entry<String, Double> collocate : dn.sharedCollocates().entrySet()) {
                collocFrequency.merge(collocate.getKey(), 1, Integer::sum);
                collocTotalScore.merge(collocate.getKey(), collocate.getValue(), Double::sum);
            }
        }
        int minNounsForCore = Math.max(2, discoveredNouns.size() / 3);
        List<CoreCollocate> coreCollocates = new ArrayList<>();
        for (String collocate : seedCollocScores.keySet()) {
            int freq = collocFrequency.getOrDefault(collocate, 0);
            if (freq >= minNounsForCore) {
                double totalScore = collocTotalScore.getOrDefault(collocate, 0.0);
                double seedScore = seedCollocScores.get(collocate);
                coreCollocates.add(new CoreCollocate(
                    collocate, freq, discoveredNouns.size(), seedScore, totalScore / freq));
            }
        }
        coreCollocates.sort((a, b) -> {
            int cmp = Integer.compare(b.sharedByCount(), a.sharedByCount());
            return cmp != 0 ? cmp : Double.compare(b.avgLogDice(), a.avgLogDice());
        });
        return coreCollocates;
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
     * <p>Design note: unlike {@link #exploreByPattern} and {@link #exploreMultiSeed}, this method
     * does not accept a {@link pl.marcinmilkowski.word_sketch.config.RelationConfig} parameter.
     * {@link CollocateProfileComparator} aggregates collocates across all loaded relations rather
     * than filtering to a single relation type, which gives a broader cross-relational profile.</p>
     *
     * @param seedNouns   Nouns to compare (e.g., "theory", "model", "hypothesis"); must not be null or empty
     * @param opts        exploration options; {@code topCollocates} and {@code minLogDice} are used
     * @return ComparisonResult with graded adjective profiles
     * @throws IllegalArgumentException if fewer than 2 seed nouns are provided
     */
    public @NonNull ComparisonResult compareCollocateProfiles(
            @NonNull Set<String> seedNouns, @NonNull ExplorationOptions opts) throws IOException {
        if (seedNouns.size() < 2) {
            throw new IllegalArgumentException(
                "Comparison requires at least 2 seed nouns for a meaningful result");
        }
        return comparator.compareCollocateProfiles(seedNouns, opts.minLogDice(), opts.topCollocates());
    }

    /**
     * Fetch example sentences for a collocate-headword pair using the provided relation pattern.
     *
     * @param collocate      The collocate lemma (e.g. an adjective)
     * @param headword       The headword lemma (e.g. a noun)
     * @param relationConfig The relation config defining how collocate and headword co-occur
     * @param opts           Options controlling how many examples to fetch
     * @return List of example sentences showing the collocate-headword combination
     */
    public @NonNull List<String> fetchExamples(@NonNull String collocate, @NonNull String headword,
            @NonNull RelationConfig relationConfig, @NonNull FetchExamplesOptions opts)
            throws IOException {
        String bcqlQuery = RelationPatternBuilder.buildFullPattern(relationConfig, headword.toLowerCase(), collocate.toLowerCase());

        List<QueryResults.CollocateResult> results = executor.executeBcqlQuery(bcqlQuery, opts.maxExamples());

        return results.stream()
            .map(QueryResults.CollocateResult::sentence)
            .distinct()
            .limit(opts.maxExamples())
            .collect(Collectors.toList());
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
        return multiSeedExplorer.findCollocateIntersection(seeds, relationConfig, opts.minLogDice(), opts.topCollocates(), opts.minShared());
    }

}
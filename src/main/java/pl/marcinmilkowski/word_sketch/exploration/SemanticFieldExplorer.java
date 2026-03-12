package pl.marcinmilkowski.word_sketch.exploration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.config.RelationPatternUtils;
import pl.marcinmilkowski.word_sketch.config.RelationUtils;
import pl.marcinmilkowski.word_sketch.model.exploration.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.exploration.CoreCollocate;
import pl.marcinmilkowski.word_sketch.model.exploration.DiscoveredNoun;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions;
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
public class SemanticFieldExplorer implements ExplorationService {

    private static final Logger logger = LoggerFactory.getLogger(SemanticFieldExplorer.class);

    private static final String FALLBACK_NOUN_PATTERN = "[xpos=\"NN.*\"]";

    private final QueryExecutor executor;
    private final CollocateProfileComparator comparator;
    private final MultiSeedExplorer multiSeedExplorer;
    private final String nounCqlConstraint;

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
        this.nounCqlConstraint = deriveNounCqlConstraint(grammarConfig);
    }

    private static String deriveNounCqlConstraint(GrammarConfig grammarConfig) {
        return RelationUtils.findBestCollocatePattern(
            grammarConfig, PosGroup.NOUN, FALLBACK_NOUN_PATTERN);
    }

    // ==================== EXPLORATION MODE ====================

    /**
     * Convenience overload that extracts pattern strings from a {@link RelationConfig},
     * mirroring the delegation style used by the multi-seed path.
     */
    public @NonNull ExplorationResult exploreByPattern(
            @NonNull String seed,
            @NonNull RelationConfig relationConfig,
            @NonNull ExplorationOptions opts,
            int reverseExpansionLimit) throws IOException {
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
            RelationPatternUtils.buildFullPattern(relationConfig, seed),
            RelationPatternUtils.buildCollocateReversePattern(relationConfig),
            opts,
            reverseExpansionLimit);
    }

    /**
     * Explore semantic field using pre-resolved BCQL pattern strings.
     *
     * <p><strong>Package-private testing seam.</strong> Production code should always call
     * {@link #exploreByPattern(String, RelationConfig, ExplorationOptions, int)}, which
     * extracts the pattern strings from a {@link RelationConfig} and preserves the config
     * abstraction. This overload exists solely to enable unit tests that exercise exploration
     * logic with programmatically constructed patterns without requiring a full grammar config.
     *
     * @param seed                   the seed noun to explore from
     * @param relationName           human-readable relation name for logging
     * @param bcqlPattern            BCQL pattern with headword already substituted
     * @param simplePattern          simple reverse-lookup pattern (e.g., {@code [xpos="JJ.*"]})
     * @param opts                   tuning parameters (topCollocates, minLogDice, minShared)
     * @param reverseExpansionLimit  maximum candidates to expand per collocate in reverse lookup
     * @return ExplorationResult with discovered semantic class
     */
    ExplorationResult exploreByPattern(
            String seed,
            String relationName,
            String bcqlPattern,
            String simplePattern,
            ExplorationOptions opts,
            int reverseExpansionLimit) throws IOException {

        if (seed == null || seed.isEmpty()) {
            throw new IllegalArgumentException("seed must not be blank");
        }

        int topPredicates = opts.topCollocates();
        int nounsPerPredicate = reverseExpansionLimit;
        double minLogDice = opts.minLogDice();
        int minShared = opts.minShared();

        String normalizedSeed = seed.toLowerCase().trim();

        logger.debug("Exploring semantic field: seed='{}', relation='{}', top={}, minShared={}, minLogDice={}", normalizedSeed, relationName, topPredicates, minShared, minLogDice);

        List<QueryResults.WordSketchResult> seedRelations = fetchSeedCollocates(
            normalizedSeed, bcqlPattern, simplePattern, minLogDice, topPredicates);

        if (seedRelations.isEmpty()) {
            logger.debug("No collocates found for seed '{}' — seed may be too rare", normalizedSeed);
            return ExplorationResult.empty(normalizedSeed);
        }


        // Build map: collocate -> logDice with seed
        Map<String, Double> seedCollocateScores = new LinkedHashMap<>();
        Map<String, Long> seedCollocateFrequencies = new LinkedHashMap<>();
        for (QueryResults.WordSketchResult r : seedRelations) {
            String lowerLemma = r.lemma().toLowerCase();
            seedCollocateScores.put(lowerLemma, r.logDice());
            seedCollocateFrequencies.put(lowerLemma, r.frequency());
        }

        Map<String, Map<String, Double>> nounProfiles = buildNounToCollocatesMap(
            seedCollocateScores, normalizedSeed, minLogDice, nounsPerPredicate, nounCqlConstraint);

        List<DiscoveredNoun> discoveredNouns = filterByMinShared(nounProfiles, minShared);
        discoveredNouns.sort((a, b) -> Double.compare(b.combinedRelevanceScore(), a.combinedRelevanceScore()));

        List<CoreCollocate> coreCollocates = identifyCoreCollocates(seedCollocateScores, discoveredNouns);

        logger.debug("Exploration complete for '{}': {} nouns discovered, {} core collocates", normalizedSeed, discoveredNouns.size(), coreCollocates.size());

        return new ExplorationResult(List.of(normalizedSeed), seedCollocateScores, seedCollocateFrequencies, discoveredNouns, coreCollocates,
            Map.of(normalizedSeed, seedCollocateScores));
    }

    // ==================== EXPLORATION PHASE HELPERS ====================

    /**
     * Phase 1: Fetch seed collocates using the BCQL pattern, with fallback to simplePattern.
     *
     * <h3>Query strategy: executeSurfacePattern primary, executeCollocations fallback</h3>
     *
     * <p><b>Primary — {@code executeSurfacePattern(bcqlPattern, ...)}:</b>
     * The BCQL pattern encodes the full grammatical context from the grammar config (e.g.
     * {@code [lemma="theory"] [xpos="VB.*"]} for SUBJECT_OF). It identifies collocates that
     * co-occur with the seed in the expected syntactic position, which yields the highest-quality
     * results but requires the seed to appear frequently enough to generate matches.</p>
     *
     * <p><b>Fallback — {@code executeCollocations(seed, simplePattern, ...)}:</b>
     * A POS-group-only pattern (e.g. {@code [xpos="JJ.*"]}) passed to the dependency-aware
     * collocation index. This bypasses syntactic structure but is more reliably populated for
     * low-frequency seeds. The trade-off is lower precision: results reflect raw co-occurrence
     * rather than a specific grammatical relation.</p>
     *
     * <p><b>Why not use {@code executeCollocations} everywhere?</b>
     * {@code executeCollocations} relies on the precomputed collocation index and does not
     * evaluate positional CQL constraints, so it cannot enforce the full grammatical structure
     * that a BCQL pattern captures. Using {@code executeSurfacePattern} first maximises
     * grammatical precision; only if that yields nothing do we fall back to the looser approach.</p>
     *
     * <p><b>Why {@link MultiSeedExplorer} does not apply this fallback:</b>
     * Multi-seed exploration compares collocates across seeds and requires a consistent
     * retrieval strategy for fair cross-seed comparison. Mixing BCQL results for one seed with
     * simple-pattern results for another would make collocate scores incomparable. Seeds that
     * return no BCQL results naturally contribute nothing to the intersection, which is the
     * correct behaviour. See {@link MultiSeedExplorer#fetchCollocatesPerSeed}.</p>
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
     *
     * <p><b>I/O fan-out:</b> issues one {@code executeCollocations} call per collocate in
     * {@code seedCollocateScores}, so up to {@code topPredicates} sequential network/disk
     * round-trips may occur. Keep {@code topPredicates} small (≤ 20) for interactive use.</p>
     */
    private Map<String, Map<String, Double>> buildNounToCollocatesMap(
            Map<String, Double> seedCollocateScores, String seed,
            double minLogDice, int nounsPerPredicate, String nounConstraint) throws IOException {
        Map<String, Map<String, Double>> nounProfiles = new LinkedHashMap<>();
        for (String collocate : seedCollocateScores.keySet()) {
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
            Map<String, Double> collocateScores = entry.getValue();
            int sharedCount = collocateScores.size();
            if (sharedCount < minShared) continue;
            double combinedRelevanceScore = collocateScores.values().stream().mapToDouble(Double::doubleValue).sum();
            double avgLogDice = combinedRelevanceScore / sharedCount;
            discoveredNouns.add(new DiscoveredNoun(
                noun, collocateScores, sharedCount, combinedRelevanceScore, avgLogDice));
        }
        return discoveredNouns;
    }

    /**
     * Phase 4: Identify core collocates — those shared by enough discovered nouns.
     */
    private List<CoreCollocate> identifyCoreCollocates(
            Map<String, Double> seedCollocateScores, List<DiscoveredNoun> discoveredNouns) {
        Map<String, Integer> collocateFrequency = new LinkedHashMap<>();
        Map<String, Double> collocateTotalScore = new LinkedHashMap<>();
        for (DiscoveredNoun dn : discoveredNouns) {
            for (Map.Entry<String, Double> collocate : dn.sharedCollocates().entrySet()) {
                collocateFrequency.merge(collocate.getKey(), 1, Integer::sum);
                collocateTotalScore.merge(collocate.getKey(), collocate.getValue(), Double::sum);
            }
        }
        int minNounsForCore = Math.max(2, discoveredNouns.size() / 3);
        List<CoreCollocate> coreCollocates = new ArrayList<>();
        for (String collocate : seedCollocateScores.keySet()) {
            int freq = collocateFrequency.getOrDefault(collocate, 0);
            if (freq >= minNounsForCore) {
                double totalScore = collocateTotalScore.getOrDefault(collocate, 0.0);
                double seedScore = seedCollocateScores.get(collocate);
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
     * @param seeds       Nouns to compare (e.g., "theory", "model", "hypothesis"); must not be null or empty
     * @param opts        exploration options; {@code topCollocates} and {@code minLogDice} are used
     * @return ComparisonResult with graded adjective profiles
     */
    public @NonNull ComparisonResult compareCollocateProfiles(
            @NonNull Set<String> seeds, @NonNull ExplorationOptions opts) throws IOException {
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
        return multiSeedExplorer.findCollocateIntersection(seeds, relationConfig, opts);
    }

}
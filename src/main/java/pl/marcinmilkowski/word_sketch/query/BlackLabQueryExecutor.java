package pl.marcinmilkowski.word_sketch.query;

import nl.inl.blacklab.search.BlackLab;
import nl.inl.blacklab.search.BlackLabIndex;
import nl.inl.blacklab.search.Concordance;
import nl.inl.blacklab.search.ConcordanceType;
import nl.inl.blacklab.search.lucene.BLSpanQuery;
import nl.inl.blacklab.search.results.Concordances;
import nl.inl.blacklab.search.results.ContextSize;
import nl.inl.blacklab.search.results.Hit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pl.marcinmilkowski.word_sketch.model.sketch.*;
import nl.inl.blacklab.search.results.HitGroup;
import nl.inl.blacklab.search.results.HitGroups;
import nl.inl.blacklab.search.results.Hits;
import nl.inl.blacklab.search.results.QueryInfo;
import nl.inl.blacklab.search.textpattern.TextPattern;
import nl.inl.blacklab.search.textpattern.CompleteQuery;
import nl.inl.blacklab.queryParser.contextql.ContextualQueryLanguageParser;
import nl.inl.blacklab.exceptions.ErrorOpeningIndex;
import nl.inl.blacklab.exceptions.InvalidQuery;

import pl.marcinmilkowski.word_sketch.utils.CqlUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Query executor using BlackLab for CoNLL-U dependency tree indexing and querying.
 *
 * <p><strong>Interface delegation:</strong> this class satisfies the {@link QueryExecutor}
 * contract by delegating the heavier collocate-query mechanics to
 * {@link CollocateQueryHelper}.  The pass-through methods ({@link #executeBcqlQuery},
 * {@link #getTotalFrequency}) are thin wrappers that forward directly to the helper, keeping
 * {@code BlackLabQueryExecutor} focused on index lifecycle and query dispatch rather than
 * low-level result assembly.</p>
 */
public class BlackLabQueryExecutor implements QueryExecutor {

    private static final Logger logger = LoggerFactory.getLogger(BlackLabQueryExecutor.class);

    private final BlackLabIndex blackLabIndex;
    private final CollocateQueryHelper collocateQueryHelper;

    /**
     * Package-private constructor for unit tests.
     *
     * <p>Bypasses index-file opening so guard-clause and static-helper tests can run in CI
     * without a real BlackLab index. Callers must not invoke any method that delegates to
     * {@code blackLabIndex} or {@code collocateQueryHelper} when those are null.</p>
     */
    @SuppressWarnings("NullAway")
    BlackLabQueryExecutor(BlackLabIndex index) {
        this.blackLabIndex = index;
        this.collocateQueryHelper = index != null
                ? new CollocateQueryHelper(index)
                : new CollocateQueryHelper((BlackLabIndex) null) {};
    }

    /**
     * Opens the BlackLab index at the given path.
     *
     * <p><strong>Constructor I/O note:</strong> opening the index is inherent to BlackLab's lifecycle
     * model — the index must be open before any query can execute, and it must be explicitly closed
     * when done. There is no meaningful "empty" state for this executor, so the I/O happens in the
     * constructor by design. The constructor declares {@code throws IOException} so callers receive
     * a checked exception if the index is missing or corrupt.</p>
     */
    public BlackLabQueryExecutor(String indexPath) throws IOException {
        try {
            this.blackLabIndex = BlackLab.open(new File(indexPath));
        } catch (ErrorOpeningIndex e) {
            // ErrorOpeningIndex extends RuntimeException — BlackLab uses it to signal a missing
            // or malformed index, so we catch it explicitly and convert to a checked IOException
            // to give callers a consistent, predictable contract.
            throw new IOException("Failed to open BlackLab index at '" + indexPath + "': " + e.getMessage(), e);
        }
        this.collocateQueryHelper = new CollocateQueryHelper(blackLabIndex);
    }

    @Override
    public List<WordSketchResult> executeCollocations(
            @Nullable String lemma,
            String cqlPattern,
            double minLogDice,
            int maxResults) throws IOException {

        if (lemma == null || lemma.isEmpty()) {
            logger.debug("executeCollocations: skipping query — lemma is null or empty");
            return Collections.emptyList();
        }

        String bcql = buildBcqlWithLemmaPrepended(cqlPattern, lemma);

        CollocateQueryHelper.CollocateSearch collocateSearch = collocateQueryHelper.executeCollocateSearchWithStoredFields(lemma, bcql);
        long headwordFreq = collocateSearch.headwordFreq();
        HitGroups groups = collocateSearch.groups();

        GroupStats stats = collectFrequenciesAndPosFromGroups(groups, identity -> {
            // extractLemmaWithFallback handles CoNLL-U style matches with explicit lemma= attributes.
            String collocateLemma = BlackLabSnippetParser.extractLemmaWithFallback(identity);
            return collocateLemma.isEmpty() ? null : collocateLemma;
        });

        return collocateQueryHelper.buildAndRankCollocates(stats.freqMap(), headwordFreq, minLogDice, maxResults, stats.lemmaPosMap());
    }

    @Override
    public List<ConcordanceResult> executeCqlQuery(String cqlPattern, int maxResults) throws IOException {
        try {
            CompleteQuery cq = ContextualQueryLanguageParser.parse(blackLabIndex, cqlPattern);
            TextPattern tp = cq.pattern();
            if (tp == null) {
                return Collections.emptyList();
            }
            BLSpanQuery query = tp.toQuery(QueryInfo.create(blackLabIndex));
            Hits hits = blackLabIndex.find(query);

            List<ConcordanceResult> results = new ArrayList<>();
            Concordances concordances = hits.concordances(ContextSize.get(5, 5, Integer.MAX_VALUE), ConcordanceType.FORWARD_INDEX);

            for (int i = 0; i < Math.min(hits.size(), maxResults); i++) {
                Hit hit = hits.get(i);
                Concordance conc = concordances.get(hit);
                String[] parts;
                try {
                    parts = BlackLabSnippetParser.safeParts(conc);
                } catch (IllegalArgumentException e) {
                    logger.debug("Skipping hit with malformed concordance: {}", e.getMessage());
                    continue;
                }
                String snippet = parts[0] + parts[1] + parts[2];

                results.add(new SnippetResult(
                    snippet, hit.start(), hit.end(), String.valueOf(hit.doc())));
            }

            return results;

        } catch (InvalidQuery e) {
            throw new IllegalArgumentException("CQL parse error: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // BlackLab's search() can throw undocumented RuntimeExceptions on index corruption
            // or internal state errors; no more-specific public exception type is available.
            throw new IOException("Unexpected error executing CQL query: " + e.getMessage(), e);
        }
    }

    @Override
    public List<CollocateResult> executeBcqlQuery(String bcqlPattern, int maxResults) throws IOException {
        return collocateQueryHelper.executeBcqlQuery(bcqlPattern, maxResults);
    }


    @Override
    public long getTotalFrequency(String lemma) throws IOException {
        return collocateQueryHelper.getTotalFrequency(lemma);
    }

    /**
     * Execute a dependency relation query, optionally with a head POS constraint.
     *
     * <p>If {@code headPosConstraint} is null, matches any POS using plain lemma syntax.
     * Otherwise, restricts the head token to the given POS regex.</p>
     */
    @Override
    public List<WordSketchResult> executeDependencyPattern(
            String lemma,
            String deprel,
            double minLogDice,
            int maxResults,
            @Nullable String headPosConstraint) throws IOException {

        Objects.requireNonNull(deprel, "deprel must not be null");
        if (lemma == null || lemma.isEmpty()) {
            logger.debug("executeDependencyPattern: skipping query — lemma is null/empty");
            return Collections.emptyList();
        }

        String bcql = headPosConstraint != null
            ? String.format("[lemma=\"%s\" & xpos=\"%s\"] -%s-> _",
                            CqlUtils.escapeForRegex(lemma.toLowerCase()), headPosConstraint, deprel)
            : String.format("\"%s\" -%s-> _", CqlUtils.escapeForRegex(lemma.toLowerCase()), deprel);
        return queryAndRankDepCollocates(bcql, lemma, minLogDice, maxResults);
    }

    private List<WordSketchResult> queryAndRankDepCollocates(
            String bcql, String lemma, double minLogDice, int maxResults) throws IOException {
        CollocateQueryHelper.CollocateSearch collocateSearch = collocateQueryHelper.executeCollocateSearchWithStoredFields(lemma, bcql);
        long headwordFreq = collocateSearch.headwordFreq();
        HitGroups groups = collocateSearch.groups();

        GroupStats stats = collectFrequenciesAndPosFromGroups(groups,
            // Dependency relation identities are plain word-form strings; extractLastLemma
            // picks the dependent token directly without needing a lemma= attribute fallback.
            BlackLabSnippetParser::extractLastLemma);

        return collocateQueryHelper.buildAndRankCollocates(stats.freqMap(), headwordFreq, minLogDice, maxResults, stats.lemmaPosMap());
    }

    /** Holds the frequency and POS maps produced by {@link #collectFrequenciesAndPosFromGroups}. */
    private record GroupStats(Map<String, Long> freqMap, Map<String, String> lemmaPosMap) {}

    /**
     * Iterates over HitGroups and collects frequency and POS data using the provided lemma extractor.
     * Groups with empty identity or null/empty extracted lemmas are skipped.
     */
    private GroupStats collectFrequenciesAndPosFromGroups(
            HitGroups groups,
            Function<String, String> lemmaExtractor) {
        Map<String, Long> freqMap = new LinkedHashMap<>();
        Map<String, String> lemmaPosMap = new HashMap<>();
        for (HitGroup group : groups) {
            String identity = group.identity().toString();
            if (identity.isEmpty()) continue;
            String lemma = lemmaExtractor.apply(identity);
            if (lemma == null || lemma.isEmpty()) continue;
            String key = lemma.toLowerCase();
            freqMap.merge(key, group.size(), Long::sum);
            String pos = BlackLabSnippetParser.extractPosFromMatch(identity);
            if (pos != null) lemmaPosMap.put(key, pos);
        }
        return new GroupStats(freqMap, lemmaPosMap);
    }

    /**
     * Execute a surface pattern query for word sketches.
     * Properly handles labeled capture groups (1: for head, 2: for collocate).
     * The headword lemma is extracted from the {@code lemma=} attribute in the pattern.
     *
     * @throws IllegalArgumentException if the headword lemma cannot be extracted from the pattern
     */
    @Override
    public List<WordSketchResult> executeSurfacePattern(
            String bcqlPattern,
            double minLogDice, int maxResults) throws IOException {

        String lemma = CqlUtils.extractHeadword(bcqlPattern);
        if (lemma == null || lemma.isEmpty()) {
            throw new IllegalArgumentException(
                "executeSurfacePattern: lemma not extractable from pattern — " +
                "the pattern must contain a labeled head token with a lemma attribute, " +
                "e.g. 1:[lemma=\"word\"] (got: " + bcqlPattern + ")");
        }

        int collocatePos = CqlUtils.findLabelTokenIndex(bcqlPattern, 2);
        CollocateQueryHelper.CollocateSearch collocateSearch = collocateQueryHelper.executeCollocateSearch(lemma, bcqlPattern);
        long headwordFreq = collocateSearch.headwordFreq();
        HitGroups groups = collocateSearch.groups();

        GroupStats stats = collectFrequenciesAndPosFromGroups(groups,
                identity -> {
                    // sentinel: collocatePos == -1 means label "2:" is absent from the pattern;
                    // fall back to extractLastLemma. Uses < 0 to handle any negative sentinel value.
                    if (collocatePos < 0) {
                        return BlackLabSnippetParser.extractLastLemma(identity);
                    }
                    // Primary path: identity is XML — count lemma="..." attributes up to 1-based collocatePos.
                    String collocate = BlackLabSnippetParser.extractCollocateFromXmlByPosition(identity, collocatePos);
                    if (collocate == null || collocate.isEmpty()) {
                        // Fallback path: identity is plain whitespace-separated text — collocatePos is
                        // reused as a 1-based word index (same integer value, different counting scheme).
                        collocate = BlackLabSnippetParser.extractPlainTextTokenAt(identity, collocatePos);
                    }
                    return collocate;
                });

        return collocateQueryHelper.buildAndRankCollocates(stats.freqMap(), headwordFreq, minLogDice, maxResults, stats.lemmaPosMap());
    }

    @Override
    public String getExecutorType() {
        return "blacklab";
    }

    @Override
    public void close() throws IOException {
        blackLabIndex.close();
    }

    /**
     * Builds a BCQL pattern string from a CQL template and lemma.
     *
     * <p><strong>Canonical format:</strong> {@code [constraint]}-prefix patterns, where the lemma
     * is prepended as a quoted token. Example: {@code [xpos="JJ.*"]} becomes
     * {@code "house" [xpos="JJ.*"]}. This is the format generated by all current grammar configs.
     *
     * @param cqlPattern  CQL pattern that must start with {@code [}
     * @param lemma       headword to prepend
     * @throws IllegalArgumentException if {@code cqlPattern} does not start with {@code [}
     */
    static String buildBcqlWithLemmaPrepended(String cqlPattern, String lemma) {
        if (cqlPattern == null) {
            throw new IllegalArgumentException("cqlPattern must not be null");
        }
        if (cqlPattern.startsWith("[")) {
            return String.format("\"%s\" %s", CqlUtils.escapeForRegex(lemma.toLowerCase()), cqlPattern);
        } else {
            throw new IllegalArgumentException("Unrecognized CQL pattern format: " + cqlPattern);
        }
    }
}

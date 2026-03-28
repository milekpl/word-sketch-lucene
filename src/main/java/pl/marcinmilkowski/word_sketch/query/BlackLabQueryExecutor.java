package pl.marcinmilkowski.word_sketch.query;

import nl.inl.blacklab.resultproperty.HitProperty;
import nl.inl.blacklab.resultproperty.HitPropertyCaptureGroup;
import nl.inl.blacklab.resultproperty.HitPropertyMultiple;
import nl.inl.blacklab.resultproperty.PropertyValue;
import nl.inl.blacklab.search.BlackLab;
import nl.inl.blacklab.search.BlackLabIndex;
import nl.inl.blacklab.search.Concordance;
import nl.inl.blacklab.search.ConcordanceType;
import nl.inl.blacklab.search.indexmetadata.Annotation;
import nl.inl.blacklab.search.indexmetadata.MatchSensitivity;
import nl.inl.blacklab.search.lucene.BLSpanQuery;
import nl.inl.blacklab.search.lucene.RelationInfo;
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

import org.jspecify.annotations.NonNull;
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
                : CollocateQueryHelper.forTesting();
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
            @NonNull String lemma,
            String cqlPattern,
            double minLogDice,
            int maxResults) throws IOException {

        if (lemma == null || lemma.isEmpty()) {
            throw new IllegalArgumentException(
                "executeCollocations: lemma must not be null or empty");
        }

        String bcql = buildBcqlWithLemmaPrepended(cqlPattern, lemma);
        String captureGroupedBcql = buildCaptureGroupedBcql(cqlPattern, lemma);

        CollocateQueryHelper.CollocateSearch collocateSearch;
        HitProperty effectiveGroupBy = null;
        if (captureGroupedBcql != null) {
            HitProperty groupBy = createSurfaceCollocateGroupProperty();
            if (groupBy != null) {
                try {
                    collocateSearch = collocateQueryHelper.executeCollocateSearch(lemma, captureGroupedBcql, groupBy, false);
                    effectiveGroupBy = groupBy;
                } catch (IllegalArgumentException e) {
                    logger.debug("Lemma capture grouping unavailable for pattern '{}', falling back: {}",
                            captureGroupedBcql, e.getMessage());
                    collocateSearch = executeStoredHitTextCollocateSearch(lemma, bcql);
                }
            } else {
                collocateSearch = executeStoredHitTextCollocateSearch(lemma, bcql);
            }
        } else {
            collocateSearch = executeStoredHitTextCollocateSearch(lemma, bcql);
        }
        long headwordFreq = collocateSearch.headwordFreq();
        HitGroups groups = collocateSearch.groups();

        CollocateHitStats stats = effectiveGroupBy != null
                ? collectFrequenciesAndPosFromPropertyGroups(groups)
                : collectFrequenciesAndPosFromGroups(groups,
                        identity -> BlackLabSnippetParser.extractLemmaWithFallback(identity));

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

                results.add(new ConcordanceHit(
                    snippet, hit.start(), hit.end(), String.valueOf(hit.doc())));
            }

            return results;

        } catch (InvalidQuery e) {
            throw new IllegalArgumentException("CQL parse error: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // BlackLab's search() can throw undocumented RuntimeExceptions on index corruption
            // or internal state errors; no more-specific public exception type is available.
            // NullPointerExceptions (programming bugs) propagate naturally from here.
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
            throw new IllegalArgumentException(
                "executeDependencyPattern: lemma must not be null or empty");
        }

        String bcql = headPosConstraint != null
            ? String.format("[lemma=\"%s\" & xpos=\"%s\"] -%s-> _",
                            CqlUtils.escapeForRegex(lemma.toLowerCase()), headPosConstraint, deprel)
            : String.format("\"%s\" -%s-> _", CqlUtils.escapeForRegex(lemma.toLowerCase()), deprel);
        return queryAndRankDepCollocates(bcql, lemma, minLogDice, maxResults);
    }

    private List<WordSketchResult> queryAndRankDepCollocates(
            String bcql, String lemma, double minLogDice, int maxResults) throws IOException {
        CollocateQueryHelper.CollocateSearch collocateSearch =
            executeStoredHitTextCollocateSearch(lemma, bcql);
        long headwordFreq = collocateSearch.headwordFreq();
        HitGroups groups = collocateSearch.groups();

        CollocateHitStats stats = collectFrequenciesAndPosFromGroups(groups,
            BlackLabSnippetParser::extractLemmaWithFallback);

        return collocateQueryHelper.buildAndRankCollocates(stats.freqMap(), headwordFreq, minLogDice, maxResults, stats.lemmaPosMap());
    }

    /** Holds the frequency and POS maps produced by {@link #collectFrequenciesAndPosFromGroups}. */
    private record CollocateHitStats(Map<String, Long> freqMap, Map<String, String> lemmaPosMap) {}
    static record ParsedGroupIdentity(@Nullable String lemma, @Nullable String pos) {}

    /**
     * Iterates over HitGroups and collects frequency and POS data using the provided lemma extractor.
     * Groups with empty identity or null/empty extracted lemmas are skipped.
     */
    private CollocateHitStats collectFrequenciesAndPosFromGroups(
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
        return new CollocateHitStats(freqMap, lemmaPosMap);
    }

    /**
     * Iterates over HitGroups whose identities are grouped by collocate capture properties
     * (lemma or lemma+xpos) rather than full hit text.
     */
    private CollocateHitStats collectFrequenciesAndPosFromPropertyGroups(HitGroups groups) {
        Map<String, Long> freqMap = new LinkedHashMap<>();
        Map<String, String> lemmaPosMap = new HashMap<>();
        for (HitGroup group : groups) {
            ParsedGroupIdentity parsed = parseGroupedCollocateIdentity(group.identity());
            String lemma = parsed.lemma();
            if (lemma == null || lemma.isBlank()) continue;
            String key = lemma.toLowerCase();
            freqMap.merge(key, group.size(), Long::sum);
            String pos = parsed.pos();
            if (pos != null && !pos.isBlank()) {
                lemmaPosMap.put(key, pos);
            }
        }
        return new CollocateHitStats(freqMap, lemmaPosMap);
    }

    static ParsedGroupIdentity parseGroupedCollocateIdentity(PropertyValue identity) {
        List<PropertyValue> values = identity.valuesList();
        if (values.isEmpty()) {
            return new ParsedGroupIdentity(null, null);
        }
        String lemma = values.get(0).toString();
        if (lemma == null || lemma.isBlank()) {
            return new ParsedGroupIdentity(null, null);
        }
        String pos = values.size() > 1 ? values.get(1).toString() : null;
        return new ParsedGroupIdentity(lemma, pos);
    }

    /**
     * Extracts the collocate lemma from a HitGroup identity string.
     *
     * <p>When {@code collocateLabelIndex} is negative (label {@code "2:"} absent from the pattern),
     * falls back to extracting the last lemma in the identity. Otherwise tries XML first
     * (counting {@code <w lemma="...">} elements by 1-based position), then plain-text
     * as a fallback — both XML and plain-text positions are equivalent 1-based token indices.</p>
     */
    @Nullable
    private static String extractCollocateFromIdentity(String identity, int collocateLabelIndex) {
        if (collocateLabelIndex < 0) {
            return BlackLabSnippetParser.extractLastLemma(identity);
        }
        String collocate = BlackLabSnippetParser.extractCollocateFromXmlByPosition(identity, collocateLabelIndex);
        if (collocate == null || collocate.isEmpty()) {
            collocate = BlackLabSnippetParser.extractPlainTextTokenAt(identity, collocateLabelIndex);
        }
        return collocate;
    }

    /**
     * Execute a surface pattern query for word sketches.
     * Properly handles labeled capture groups (1: for head, 2: for collocate).
     * The headword lemma is extracted from the {@code lemma=} attribute in the pattern.
     *
     * @throws IllegalArgumentException if the headword lemma cannot be extracted from the pattern
     */
    @Override
    public List<WordSketchResult> executeSurfaceCollocations(
            String bcqlPattern,
            double minLogDice, int maxResults) throws IOException {

        String lemma = CqlUtils.extractHeadword(bcqlPattern);
        if (lemma == null || lemma.isEmpty()) {
            throw new IllegalArgumentException(
                "executeSurfaceCollocations: lemma not extractable from pattern — " +
                "the pattern must contain a labeled head token with a lemma attribute, " +
                "e.g. 1:[lemma=\"word\"] (got: " + bcqlPattern + ")");
        }

        int collocateLabelIndex = CqlUtils.findLabelTokenIndex(bcqlPattern, 2);
        HitProperty groupBy = collocateLabelIndex >= 0 ? createSurfaceCollocateGroupProperty() : null;

        CollocateQueryHelper.CollocateSearch collocateSearch;
        HitProperty effectiveGroupBy = groupBy;
        if (groupBy != null) {
            try {
                collocateSearch = collocateQueryHelper.executeCollocateSearch(lemma, bcqlPattern, groupBy, false);
            } catch (IllegalArgumentException e) {
                // Capture group '2' not registered for this pattern (e.g. POS tag absent from
                // index vocabulary causing an empty query with no registered match infos).
                // Fall back to full-hit-text grouping with identity-based collocate extraction.
                logger.debug("Capture group grouping unavailable for pattern '{}', falling back: {}",
                        bcqlPattern, e.getMessage());
                effectiveGroupBy = null;
                collocateSearch = executeStoredHitTextCollocateSearch(lemma, bcqlPattern);
            }
        } else {
            collocateSearch = executeStoredHitTextCollocateSearch(lemma, bcqlPattern);
        }
        long headwordFreq = collocateSearch.headwordFreq();
        HitGroups groups = collocateSearch.groups();

        CollocateHitStats stats = effectiveGroupBy != null
                ? collectFrequenciesAndPosFromPropertyGroups(groups)
                : collectFrequenciesAndPosFromGroups(groups,
                        identity -> extractCollocateFromIdentity(identity, collocateLabelIndex));

        return collocateQueryHelper.buildAndRankCollocates(stats.freqMap(), headwordFreq, minLogDice, maxResults, stats.lemmaPosMap());
    }

    @Nullable
    private HitProperty createSurfaceCollocateGroupProperty() {
        Annotation lemmaAnnotation = blackLabIndex.mainAnnotatedField().annotation("lemma");
        if (lemmaAnnotation == null) {
            logger.debug("Surface collocate capture grouping unavailable: lemma annotation is missing");
            return null;
        }
        HitProperty lemmaCapture = new HitPropertyCaptureGroup(
                blackLabIndex,
                lemmaAnnotation,
                MatchSensitivity.INSENSITIVE,
                "2",
                RelationInfo.SpanMode.FULL_SPAN);

        Annotation xposAnnotation = blackLabIndex.mainAnnotatedField().annotation("xpos");
        if (xposAnnotation == null) {
            return lemmaCapture;
        }

        HitProperty xposCapture = new HitPropertyCaptureGroup(
                blackLabIndex,
                xposAnnotation,
                MatchSensitivity.SENSITIVE,
                "2",
                RelationInfo.SpanMode.FULL_SPAN);
        return new HitPropertyMultiple(lemmaCapture, xposCapture);
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

    @Nullable
    static String buildCaptureGroupedBcql(String cqlPattern, String lemma) {
        if (cqlPattern == null || lemma == null) {
            return null;
        }
        String trimmedPattern = cqlPattern.trim();
        List<String> tokens = CqlUtils.splitCqlTokens(trimmedPattern);
        if (tokens.size() != 1 || !trimmedPattern.equals(tokens.get(0))) {
            return null;
        }
        return String.format("1:[lemma=\"%s\"] 2:%s",
                CqlUtils.escapeForRegex(lemma.toLowerCase()), tokens.get(0));
    }

    private CollocateQueryHelper.CollocateSearch executeStoredHitTextCollocateSearch(String lemma, String bcql)
            throws IOException {
        return collocateQueryHelper.executeCollocateSearch(lemma, bcql, true);
    }
}

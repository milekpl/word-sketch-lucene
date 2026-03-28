package pl.marcinmilkowski.word_sketch.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import nl.inl.blacklab.exceptions.InvalidQuery;
import nl.inl.blacklab.resultproperty.HitProperty;
import nl.inl.blacklab.resultproperty.HitPropertyHitText;

import pl.marcinmilkowski.word_sketch.model.sketch.*;
import nl.inl.blacklab.search.BlackLabIndex;import nl.inl.blacklab.search.ConcordanceType;
import nl.inl.blacklab.search.lucene.BLSpanQuery;
import nl.inl.blacklab.search.indexmetadata.AnnotatedField;
import nl.inl.blacklab.search.indexmetadata.Annotation;
import nl.inl.blacklab.search.indexmetadata.AnnotationSensitivity;
import nl.inl.blacklab.search.indexmetadata.MatchSensitivity;
import nl.inl.blacklab.search.Concordance;
import nl.inl.blacklab.search.results.Concordances;
import nl.inl.blacklab.search.results.ContextSize;
import nl.inl.blacklab.search.results.Hit;
import nl.inl.blacklab.search.results.HitGroup;
import nl.inl.blacklab.search.results.HitGroups;
import nl.inl.blacklab.search.results.Hits;
import nl.inl.blacklab.search.results.QueryInfo;
import nl.inl.blacklab.search.results.Results;
import nl.inl.blacklab.search.TermFrequencyList;
import nl.inl.blacklab.search.textpattern.TextPattern;
import nl.inl.blacklab.searches.SearchHits;

import pl.marcinmilkowski.word_sketch.utils.LogDiceUtils;
import pl.marcinmilkowski.word_sketch.utils.CqlUtils;


import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Package-private helper that encapsulates collocate search, scoring, and ranking logic
 * shared across several methods of {@link BlackLabQueryExecutor}.
 */
class CollocateQueryHelper {
    private static final Logger logger = LoggerFactory.getLogger(CollocateQueryHelper.class);
    private static final int MAX_TOTAL_FREQUENCY_CACHE_ENTRIES = 20_000;

    @Nullable
    private final BlackLabIndex index;
    private final Map<String, Long> totalFrequencyCache;

    /** Creates a helper bound to the given BlackLab index. */
    CollocateQueryHelper(@NonNull BlackLabIndex index) {
        this.index = index;
        this.totalFrequencyCache = createTotalFrequencyCache();
    }

    /**
     * Creates a no-op helper for unit tests that do not need corpus I/O.
     * Any method that delegates to the BlackLab index will throw {@link NullPointerException}
     * if called on this instance — only call methods that are purely computational.
     */
    static CollocateQueryHelper forTesting() {
        return new CollocateQueryHelper();
    }

    /** No-arg constructor for test-only instances — index is null. */
    private CollocateQueryHelper() {
        this.index = null;
        this.totalFrequencyCache = createTotalFrequencyCache();
    }

    /**
     * Guards against calling index-dependent methods on a test-only instance created with
     * {@link #forTesting()}. Throws a descriptive {@link IllegalStateException} rather than
     * letting a raw {@link NullPointerException} propagate from deep inside a query method.
     */
    private void assertIndexAvailable() {
        if (this.index == null) {
            throw new IllegalStateException(
                "CollocateQueryHelper.forTesting() instances do not have a BlackLab index. "
                + "Only call purely-computational methods (scoring, filtering) on test instances.");
        }
    }

    // -------------------------------------------------------------------------
    // Frequency lookup
    // -------------------------------------------------------------------------

    /**
     * Returns total token frequency for the given lemma in the index.
     * Returns {@code 0L} if the lemma annotation is not found.
     *
     * @throws IOException wrapping any RuntimeException thrown by the BlackLab index
     */
    long getTotalFrequency(String lemma) throws IOException {
        String normalizedLemma = lemma.toLowerCase();
        synchronized (totalFrequencyCache) {
            Long cached = totalFrequencyCache.get(normalizedLemma);
            if (cached != null) {
                return cached;
            }
        }
        long frequency = loadTotalFrequency(normalizedLemma);
        synchronized (totalFrequencyCache) {
            totalFrequencyCache.put(normalizedLemma, frequency);
        }
        return frequency;
    }

    /**
     * Loads total token frequency for the given lemma from the BlackLab index without consulting
     * the process-level cache. Tests override this method to supply deterministic corpus frequencies.
     */
    long loadTotalFrequency(String lemma) throws IOException {
        assertIndexAvailable();
        try {
            BlackLabIndex idx = this.index;
            AnnotatedField field = idx.mainAnnotatedField();
            Annotation annotation = field.annotation("lemma");
            if (annotation == null) {
                return 0L;
            }
            AnnotationSensitivity sensitivity = annotation.sensitivity(MatchSensitivity.INSENSITIVE);
            TermFrequencyList tfl = idx.termFrequencies(sensitivity, null, Set.of(lemma.toLowerCase()));
            return tfl.frequency(lemma.toLowerCase());
        } catch (RuntimeException e) {
            throw new IOException("Unexpected failure retrieving frequency for lemma '" + lemma + "'", e);
        }
    }

    private static Map<String, Long> createTotalFrequencyCache() {
        return Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > MAX_TOTAL_FREQUENCY_CACHE_ENTRIES;
            }
        });
    }

    // -------------------------------------------------------------------------
    // Grouped collocate search (shared by executeCollocations, executeSurfaceCollocations,
    // executeDependencyPattern)
    // -------------------------------------------------------------------------

    /** Holds the parsed search results needed for collocate ranking. */
    record CollocateSearch(long headwordFreq, HitGroups groups) {}

    /**
     * Parses {@code bcqlPattern}, executes the grouped collocate search, and returns
     * headword frequency with grouped hits.
     *
     * @param lemma           the headword lemma used to fetch total corpus frequency
     * @param bcqlPattern     the BCQL pattern to search
     * @param withStoredHits  {@code true} to request stored XML hit fields (needed when
     *                        callers read per-hit XML content); {@code false} for the
     *                        lighter group-stats variant
     */
    CollocateSearch executeCollocateSearch(String lemma, String bcqlPattern, boolean withStoredHits)
            throws IOException {
        assertIndexAvailable();
        return executeCollocateSearch(
                lemma,
                bcqlPattern,
                new HitPropertyHitText(this.index, MatchSensitivity.INSENSITIVE),
                withStoredHits);
    }

    /**
     * Executes a grouped collocate search using the provided grouping property.
     *
     * <p>Callers that only need grouped counts should pass {@code withStoredHits = false},
     * which uses BlackLab's stats-only grouping path and avoids materializing grouped hits.</p>
     */
    CollocateSearch executeCollocateSearch(
            String lemma,
            String bcqlPattern,
            HitProperty groupBy,
            boolean withStoredHits) throws IOException {
        return performCollocateSearch(lemma, bcqlPattern, groupBy, withStoredHits);
    }

    private CollocateSearch performCollocateSearch(
            String lemma,
            String bcqlPattern,
            HitProperty groupBy,
            boolean withStoredHits) throws IOException {
        assertIndexAvailable();
        try {
            BlackLabIndex idx = this.index;
            TextPattern pattern = nl.inl.blacklab.queryParser.corpusql.CorpusQueryLanguageParser
                    .parse(bcqlPattern, "lemma");
            BLSpanQuery query = pattern.toQuery(QueryInfo.create(idx));
            long headwordFreq = getTotalFrequency(lemma);
            SearchHits searchHits = idx.search().find(query);
            HitGroups groups = withStoredHits
                    ? searchHits.groupWithStoredHits(groupBy, Results.NO_LIMIT).execute()
                    : searchHits.groupStats(groupBy, Results.NO_LIMIT).execute();
            return new CollocateSearch(headwordFreq, groups);
        } catch (InvalidQuery e) {
            throw new IllegalArgumentException("BCQL parse error: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Collocate ranking (shared by executeCollocations, executeSurfaceCollocations,
    // executeDependencyPattern)
    // -------------------------------------------------------------------------

    /**
     * Build and rank collocate results from a frequency map using logDice scoring.
     *
     * @param freqMap       joint co-occurrence frequencies (collocate lemma → count)
     * @param headwordFreq  total corpus frequency of the headword
     * @param minLogDice    minimum logDice threshold; results below this are discarded
     * @param maxResults    maximum number of results to return
     * @param posMap        collocate-lemma → POS tag map; pass an empty map when POS
     *                      information is unavailable (placed last as it is optional)
     */
    List<WordSketchResult> buildAndRankCollocates(
            Map<String, Long> freqMap,
            long headwordFreq,
            double minLogDice,
            int maxResults,
            Map<String, String> posMap) throws IOException {
        // Corpus frequencies are fetched lazily: only look up a collocate's total corpus frequency
        // when its upper-bound logDice (computed without the corpus lookup) could reach minLogDice.
        // Upper bound: logDice is maximised when corpusFreq == jointFreq (the collocate appears
        // only with this headword), so logDice_max = LogDiceUtils.compute(j, h, j).
        // If logDice_max < minLogDice the entry is skipped and the I/O call is avoided entirely.
        Map<String, Long> corpusFreqCache = new HashMap<>();

        List<WordSketchResult> results = new ArrayList<>();
        for (Map.Entry<String, Long> entry : freqMap.entrySet()) {
            String collocateLemma = entry.getKey();
            long jointFreq = entry.getValue();

            if (headwordFreq <= 0 || jointFreq <= 0) continue;

            double logDiceMax = LogDiceUtils.compute(jointFreq, headwordFreq, jointFreq);
            if (logDiceMax < minLogDice) continue;

            Long cached = corpusFreqCache.get(collocateLemma);
            if (cached == null) {
                cached = getTotalFrequency(collocateLemma);
                corpusFreqCache.put(collocateLemma, cached);
            }
            long collocateFreq = cached;

            double logDice = collocateFreq > 0
                    ? LogDiceUtils.compute(jointFreq, headwordFreq, collocateFreq) : 0.0;

            if (logDice >= minLogDice) {
                double relFreq = LogDiceUtils.relativeFrequency(jointFreq, headwordFreq);
                String pos = posMap.getOrDefault(collocateLemma, WordSketchResult.UNKNOWN_POS);
                results.add(new WordSketchResult(
                        collocateLemma, pos, jointFreq, logDice, relFreq, Collections.emptyList()));
            }
        }
        return results.stream()
                .sorted(Comparator.comparingDouble(WordSketchResult::logDice).reversed())
                .limit(maxResults)
                .toList();
    }

    // -------------------------------------------------------------------------
    // BCQL concordance query
    // -------------------------------------------------------------------------

    /**
     * BCQL concordance query: finds hits for {@code bcqlPattern} and returns them as plain
     * concordance entries in document order. No logDice scoring or ranking is performed.
     *
     * <p>This convenience overload starts at offset 0 and returns at most {@code maxResults}
     * hits. It is the method used by {@link pl.marcinmilkowski.word_sketch.api.ConcordanceHandlers}
     * and {@link pl.marcinmilkowski.word_sketch.exploration.SemanticFieldExplorer}, which do not
     * need pagination.</p>
     *
     * @param bcqlPattern  BCQL pattern; labeled positions (e.g. {@code 2:}) extract a
     *                     collocate lemma per hit
     * @param maxResults   maximum hits to return (positive); use
     *                     {@link #executeBcqlPage} for paginated access with total count
     */
    List<CollocateResult> executeBcqlQuery(String bcqlPattern, int maxResults)
            throws IOException {
        return executeBcqlInternal(bcqlPattern, maxResults, 0).results();
    }

    /**
     * Paginated BCQL concordance query: returns a single page of hits together with the
     * total hit count (all pages combined). No logDice scoring or ranking is performed.
     * Hits are returned in document order.
     *
     * @param bcqlPattern  BCQL pattern; labeled positions extract a collocate lemma per hit
     * @param pageSize     number of hits to fetch for this page (positive)
     * @param offset       0-based index of the first hit on this page
     * @return             {@link BcqlPage} with total count, offset, page size, and results
     */
    BcqlPage executeBcqlPage(String bcqlPattern, int pageSize, int offset)
            throws IOException {
        return executeBcqlInternal(bcqlPattern, pageSize, offset);
    }

    private BcqlPage executeBcqlInternal(String bcqlPattern, int fetchSize, int offset)
            throws IOException {
        assertIndexAvailable();
        try {
            BlackLabIndex idx = this.index;
            TextPattern pattern = nl.inl.blacklab.queryParser.corpusql.CorpusQueryLanguageParser
                    .parse(bcqlPattern, "lemma");
            BLSpanQuery query = pattern.toQuery(QueryInfo.create(idx));
            Hits hits = idx.find(query);
            long total = hits.size();
            int clampedFetch = (fetchSize <= 0 || offset >= total)
                    ? 0
                    : (int) Math.min((long) fetchSize, total - offset);

            if (clampedFetch == 0) return new BcqlPage(total, offset, fetchSize, List.of());

            int collocatePos = CqlUtils.findLabelTokenIndex(bcqlPattern, 2);
            Hits window = hits.window((long) offset, (long) (offset + clampedFetch));
            Concordances concordances = window.concordances(
                    ContextSize.get(60, 60, Integer.MAX_VALUE), ConcordanceType.FORWARD_INDEX);

            List<CollocateResult> results = new ArrayList<>(clampedFetch);
            for (int i = 0; i < clampedFetch; i++) {
                Hit hit = window.get(i);
                Concordance conc = concordances.get(hit);
                String[] parts = BlackLabSnippetParser.safeParts(conc);
                String leftXml  = parts[0];
                String matchXml = parts[1];
                String rightXml = parts[2];
                String xmlSnippet = BlackLabSnippetParser.trimXmlToSentence(leftXml, matchXml, rightXml);
                String leftText  = BlackLabSnippetParser.extractPlainTextFromXml(
                        BlackLabSnippetParser.trimLeftXmlAtSentence(leftXml));
                String matchText = BlackLabSnippetParser.extractPlainTextFromXml(matchXml);
                String rightText = BlackLabSnippetParser.extractPlainTextFromXml(
                        BlackLabSnippetParser.trimRightXmlAtSentence(rightXml));
                String collocateLemma = extractCollocateLemma(matchXml, collocatePos);
                String trimmedLeft  = BlackLabSnippetParser.trimLeftAtSentenceBoundary(leftText);
                String trimmedRight = BlackLabSnippetParser.trimRightAtSentenceBoundary(rightText);
                String plainLeft  = BlackLabSnippetParser.detokenize(trimmedLeft);
                String plainMatch = BlackLabSnippetParser.detokenize(matchText);
                String plainRight = BlackLabSnippetParser.detokenize(trimmedRight);
                String plainText  = BlackLabSnippetParser.trimToSentence(leftText, matchText, rightText);
                results.add(new CollocateResult(
                        plainText, xmlSnippet,
                        plainLeft, plainMatch, plainRight,
                        hit.start(), hit.end(), String.valueOf(hit.doc()),
                        collocateLemma, 0L, 0.0));
            }
            return new BcqlPage(total, offset, clampedFetch, results);
        } catch (InvalidQuery e) {
            throw new IllegalArgumentException("BCQL parse error: " + e.getMessage(), e);
        }
    }

    /**
     * Extract collocate lemma from match XML using the labeled position.
     * Falls back to the last lemma in the match XML when no labeled position is available.
     */
    static @Nullable String extractCollocateLemma(String matchXml, int collocatePos) {
        if (collocatePos > 0) {
            String extracted = BlackLabSnippetParser.extractCollocateFromXmlByPosition(matchXml, collocatePos);
            if (extracted != null && !extracted.isEmpty()) return extracted;
        }
        return BlackLabSnippetParser.extractLastLemma(matchXml);
    }
}

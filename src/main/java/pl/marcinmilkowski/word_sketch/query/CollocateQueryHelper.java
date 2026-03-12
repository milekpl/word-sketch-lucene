package pl.marcinmilkowski.word_sketch.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import nl.inl.blacklab.exceptions.InvalidQuery;
import nl.inl.blacklab.resultproperty.HitProperty;
import nl.inl.blacklab.resultproperty.HitPropertyHitText;

import pl.marcinmilkowski.word_sketch.model.QueryResults;
import nl.inl.blacklab.search.BlackLabIndex;
import nl.inl.blacklab.search.ConcordanceType;
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

import java.util.Objects;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Package-private helper that encapsulates collocate search, scoring, and ranking logic
 * shared across several methods of {@link BlackLabQueryExecutor}.
 */
class CollocateQueryHelper {
    private static final Logger logger = LoggerFactory.getLogger(CollocateQueryHelper.class);

    /** Over-fetch factor: request 3x as many hits as needed to compensate for scoring discards. */
    private static final int OVER_FETCH_FACTOR = 3;

    private final @Nullable BlackLabIndex index;

    /**
     * Creates a helper bound to the given BlackLab index.
     * All production callers must supply a non-null index.
     *
     * @param index the BlackLab index to query
     */
    CollocateQueryHelper(@NonNull BlackLabIndex index) {
        this.index = index;
    }

    /**
     * No-index constructor for test subclasses that override all I/O methods
     * ({@link #getTotalFrequency} and {@link #executeCollocateSearchWithContent}).
     * Not for production use.
     */
    CollocateQueryHelper() {
        this.index = null;
    }

    // -------------------------------------------------------------------------
    // Frequency lookup
    // -------------------------------------------------------------------------

    /**
     * Returns total token frequency for the given lemma in the index.
     * Returns {@code 0L} if the lemma annotation is not found.
     *
     * @throws IOException if an unexpected non-runtime failure occurs
     */
    long getTotalFrequency(String lemma) throws IOException {
        Objects.requireNonNull(index, "BlackLabIndex must not be null for getTotalFrequency");
        try {
            AnnotatedField field = index.mainAnnotatedField();
            Annotation annotation = field.annotation("lemma");
            if (annotation == null) {
                return 0L;
            }
            AnnotationSensitivity sensitivity = annotation.sensitivity(MatchSensitivity.INSENSITIVE);
            TermFrequencyList tfl = index.termFrequencies(sensitivity, null, Set.of(lemma.toLowerCase()));
            return tfl.frequency(lemma.toLowerCase());
        } catch (RuntimeException e) {
            throw new IOException("Unexpected failure retrieving frequency for lemma '" + lemma + "'", e);
        }
    }

    // -------------------------------------------------------------------------
    // Grouped collocate search (shared by executeCollocations, executeSurfacePattern,
    // executeDependencyPattern)
    // -------------------------------------------------------------------------

    /** Holds the parsed search results needed for collocate ranking. */
    record CollocateSearch(long headwordFreq, HitGroups groups) {}

    /**
     * Parses {@code bcqlPattern}, executes the search with stored hits (needed when the
     * iteration logic reads stored XML fields), and returns headword frequency with grouped hits.
     *
     * @param lemma       the headword lemma used to fetch total corpus frequency
     * @param bcqlPattern the BCQL pattern to search
     */
    CollocateSearch executeCollocateSearchWithContent(String lemma, String bcqlPattern)
            throws IOException {
        return performCollocateSearch(lemma, bcqlPattern, true);
    }

    /**
     * Parses {@code bcqlPattern}, executes the search with the lighter {@code group} variant
     * (no stored XML fields needed), and returns headword frequency with grouped hits.
     *
     * @param lemma       the headword lemma used to fetch total corpus frequency
     * @param bcqlPattern the BCQL pattern to search
     */
    CollocateSearch executeCollocateSearch(String lemma, String bcqlPattern)
            throws IOException {
        return performCollocateSearch(lemma, bcqlPattern, false);
    }

    private CollocateSearch performCollocateSearch(String lemma, String bcqlPattern, boolean withStoredHits)
            throws IOException {
        Objects.requireNonNull(index, "BlackLabIndex must not be null for executeCollocateSearch");
        try {
            TextPattern pattern = nl.inl.blacklab.queryParser.corpusql.CorpusQueryLanguageParser
                    .parse(bcqlPattern, "lemma");
            BLSpanQuery query = pattern.toQuery(QueryInfo.create(index));
            long headwordFreq = getTotalFrequency(lemma);
            SearchHits searchHits = index.search().find(query);
            HitProperty groupBy = new HitPropertyHitText(index, MatchSensitivity.INSENSITIVE);
            HitGroups groups = withStoredHits
                    ? searchHits.groupWithStoredHits(groupBy, Results.NO_LIMIT).execute()
                    : searchHits.group(groupBy, Results.NO_LIMIT).execute();
            return new CollocateSearch(headwordFreq, groups);
        } catch (InvalidQuery e) {
            throw new IOException("BCQL parse error: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Collocate ranking (shared by executeCollocations, executeSurfacePattern,
    // executeDependencyPattern)
    // -------------------------------------------------------------------------

    /**
     * Pre-fetches the corpus frequency for each lemma in the given collection,
     * returning a map from lemma to total frequency.
     * Avoids redundant I/O calls when the same lemma appears multiple times.
     */
    private Map<String, Long> prefetchCorpusFrequencies(Collection<String> lemmas) throws IOException {
        Map<String, Long> freqs = new HashMap<>();
        for (String lemma : lemmas) {
            freqs.put(lemma, getTotalFrequency(lemma));
        }
        return freqs;
    }

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
    List<QueryResults.WordSketchResult> buildAndRankCollocates(
            Map<String, Long> freqMap,
            long headwordFreq,
            double minLogDice,
            int maxResults,
            Map<String, String> posMap) throws IOException {
        Map<String, Long> collocateCorpusFreqs = prefetchCorpusFrequencies(freqMap.keySet());

        List<QueryResults.WordSketchResult> results = new ArrayList<>();
        for (Map.Entry<String, Long> entry : freqMap.entrySet()) {
            String collocateLemma = entry.getKey();
            long jointFreq = entry.getValue();
            long collocateFreq = collocateCorpusFreqs.getOrDefault(collocateLemma, 0L);

            double logDice = (headwordFreq > 0 && collocateFreq > 0)
                    ? LogDiceUtils.compute(jointFreq, headwordFreq, collocateFreq) : 0.0;

            if (logDice >= minLogDice) {
                double relFreq = LogDiceUtils.relativeFrequency(jointFreq, headwordFreq);
                String pos = posMap.getOrDefault(collocateLemma, QueryResults.WordSketchResult.UNKNOWN_POS);
                results.add(new QueryResults.WordSketchResult(
                        collocateLemma, pos, jointFreq, logDice, relFreq, Collections.emptyList()));
            }
        }
        return results.stream()
                .sorted(Comparator.comparingDouble(QueryResults.WordSketchResult::logDice).reversed())
                .limit(maxResults)
                .toList();
    }

    // -------------------------------------------------------------------------
    // BCQL hit-collection pipeline (used by executeBcqlQuery)
    // -------------------------------------------------------------------------

    /** Per-hit data collected in phase 1 of {@link #executeBcqlQuery}. */
    record HitRecord(String xmlSnippet, String leftText, String matchText, String rightText,
                     @Nullable String collocateLemma, int docId, int start, int end) {}

    /**
     * Full two-phase BCQL query execution: parse → collect hits → score → rank.
     */
    List<QueryResults.CollocateResult> executeBcqlQuery(String bcqlPattern, int maxResults)
            throws IOException {
        try {
            TextPattern pattern = nl.inl.blacklab.queryParser.corpusql.CorpusQueryLanguageParser
                    .parse(bcqlPattern, "lemma");
            BLSpanQuery query = pattern.toQuery(QueryInfo.create(index));

            Hits hits = index.find(query);
            String headword = CqlUtils.extractHeadword(bcqlPattern);
            long headwordFreq = headword != null ? getTotalFrequency(headword) : 0L;
            int collocatePos = CqlUtils.findLabelTokenIndex(bcqlPattern, 2);
            int sampleSize = (int) Math.min(hits.size(), (long) maxResults * OVER_FETCH_FACTOR); // safe: min ensures result ≤ maxResults * OVER_FETCH_FACTOR

            Map<String, Long> collocateFreqMap = new HashMap<>();
            List<HitRecord> hitRecords = collectHits(hits, sampleSize, collocatePos, collocateFreqMap);
            List<QueryResults.CollocateResult> scored = scoreHits(hitRecords, collocateFreqMap, headwordFreq);
            return scored.stream()
                    .sorted(Comparator.comparingDouble(QueryResults.CollocateResult::logDice).reversed())
                    .limit(maxResults)
                    .toList();

        } catch (InvalidQuery e) {
            throw new IOException("BCQL parse error: " + e.getMessage(), e);
        }
    }

    /**
     * Phase 1 — hit collection: iterate sample hits, extract text/XML and collocate lemma,
     * and accumulate per-collocate co-occurrence counts into {@code freqMapOut}.
     */
    private List<HitRecord> collectHits(Hits hits, int sampleSize, int collocatePos,
                                        Map<String, Long> freqMapOut) {
        List<HitRecord> records = new ArrayList<>(sampleSize);
        if (sampleSize == 0) return records;

        Hits sample = hits.window(0L, (long) sampleSize);
        Concordances concordances = sample.concordances(
                ContextSize.get(60, 60, Integer.MAX_VALUE), ConcordanceType.FORWARD_INDEX);

        for (int idx = 0; idx < sampleSize; idx++) {
            Hit hit = sample.get(idx);
            Concordance conc = concordances.get(hit);

            String[] parts = BlackLabSnippetParser.safeParts(conc);
            if (parts == null) {
                records.add(new HitRecord("", "", "", "", null, hit.doc(), hit.start(), hit.end()));
                continue;
            }

            String leftXml  = parts[0];
            String matchXml = parts[1];
            String rightXml = parts[2];
            String xmlSnippet = leftXml + matchXml + rightXml;
            String leftText   = BlackLabSnippetParser.extractPlainTextFromXml(
                    BlackLabSnippetParser.trimLeftXmlAtSentence(leftXml));
            String matchText  = BlackLabSnippetParser.extractPlainTextFromXml(matchXml);
            String rightText  = BlackLabSnippetParser.extractPlainTextFromXml(
                    BlackLabSnippetParser.trimRightXmlAtSentence(rightXml));
            String collocateLemma = extractCollocateLemma(matchXml, collocatePos);

            if (collocateLemma != null && !collocateLemma.isEmpty()) {
                freqMapOut.merge(collocateLemma.toLowerCase(), 1L, Long::sum);
            }
            records.add(new HitRecord(xmlSnippet, leftText, matchText, rightText,
                    collocateLemma, hit.doc(), hit.start(), hit.end()));
        }
        return records;
    }

    /**
     * Phase 2 — score each hit with logDice using corpus frequencies.
     * Frequencies for each unique collocate are pre-fetched once and cached in a local map
     * to avoid redundant {@link #getTotalFrequency} calls for the same lemma.
     * Scores all hit records and returns the full list (unsorted); the caller is responsible for
     * sorting by logDice and limiting to {@code maxResults}.
     */
    private List<QueryResults.CollocateResult> scoreHits(List<HitRecord> records,
            Map<String, Long> collocateFreqMap, long headwordFreq) throws IOException {
        // Pre-compute corpus frequency for each unique collocate to avoid one call per hit.
        Map<String, Long> collocateCorpusFreqs = prefetchCorpusFrequencies(collocateFreqMap.keySet());

        List<QueryResults.CollocateResult> results = new ArrayList<>();

        for (HitRecord rec : records) {
            String collocateLemma = rec.collocateLemma();
            if (collocateLemma == null || collocateLemma.isEmpty()) {
                String plainText = BlackLabSnippetParser.trimToSentence(
                        rec.leftText(), rec.matchText(), rec.rightText());
                results.add(new QueryResults.CollocateResult(
                        plainText, rec.xmlSnippet(),
                        rec.start(), rec.end(), String.valueOf(rec.docId()),
                        null, 0L, 0.0));
                continue;
            }

            String key = collocateLemma.toLowerCase();
            long jointFreq = collocateFreqMap.getOrDefault(key, 0L);
            if (jointFreq == 0L) {
                // Trace-level: this fires once per hit in a hot loop; debug would flood logs.
                logger.trace("No joint frequency found for collocate '{}' — logDice will be 0", collocateLemma);
            }
            long collocateFreq = collocateCorpusFreqs.getOrDefault(key, 0L);

            double logDice = (headwordFreq > 0 && collocateFreq > 0)
                    ? LogDiceUtils.compute(jointFreq, headwordFreq, collocateFreq) : 0.0;

            String plainText = BlackLabSnippetParser.trimToSentence(
                    rec.leftText(), rec.matchText(), rec.rightText());

            results.add(new QueryResults.CollocateResult(
                    plainText, rec.xmlSnippet(),
                    rec.start(), rec.end(), String.valueOf(rec.docId()),
                    collocateLemma, jointFreq, logDice));
        }
        return results;
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

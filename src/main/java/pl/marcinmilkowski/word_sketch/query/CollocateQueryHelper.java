package pl.marcinmilkowski.word_sketch.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import nl.inl.blacklab.exceptions.InvalidQuery;
import nl.inl.blacklab.resultproperty.HitProperty;
import nl.inl.blacklab.resultproperty.HitPropertyHitText;

import pl.marcinmilkowski.word_sketch.model.sketch.*;
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


import org.jspecify.annotations.Nullable;

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

    private final BlackLabIndex index;

    /**
     * Creates a helper bound to the given BlackLab index.
     *
     * <p>Accepts {@code null} only when used by test subclasses that override all I/O
     * methods ({@link #getTotalFrequency} and {@link #performCollocateSearch}), so the
     * index field is never dereferenced.  Production callers must pass a non-null index.</p>
     *
     * @param index the BlackLab index to query (null permitted for test subclasses only)
     */
    @SuppressWarnings("NullAway")
    CollocateQueryHelper(BlackLabIndex index) {
        this.index = index;
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

    // -------------------------------------------------------------------------
    // Grouped collocate search (shared by executeCollocations, executeSurfacePattern,
    // executeDependencyPattern)
    // -------------------------------------------------------------------------

    /** Holds the parsed search results needed for collocate ranking. */
    record CollocateSearch(long headwordFreq, HitGroups groups) {}

    /**
     * Parses {@code bcqlPattern}, executes the search requesting stored XML hit fields
     * (needed when the iteration logic reads per-hit XML content), and returns headword
     * frequency with grouped hits.
     *
     * @param lemma       the headword lemma used to fetch total corpus frequency
     * @param bcqlPattern the BCQL pattern to search
     */
    CollocateSearch executeCollocateSearchWithStoredFields(String lemma, String bcqlPattern)
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
        try {
            BlackLabIndex idx = this.index;
            TextPattern pattern = nl.inl.blacklab.queryParser.corpusql.CorpusQueryLanguageParser
                    .parse(bcqlPattern, "lemma");
            BLSpanQuery query = pattern.toQuery(QueryInfo.create(idx));
            long headwordFreq = getTotalFrequency(lemma);
            SearchHits searchHits = idx.search().find(query);
            HitProperty groupBy = new HitPropertyHitText(idx, MatchSensitivity.INSENSITIVE);
            HitGroups groups = withStoredHits
                    ? searchHits.groupWithStoredHits(groupBy, Results.NO_LIMIT).execute()
                    : searchHits.group(groupBy, Results.NO_LIMIT).execute();
            return new CollocateSearch(headwordFreq, groups);
        } catch (InvalidQuery e) {
            throw new IllegalArgumentException("BCQL parse error: " + e.getMessage(), e);
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
    List<WordSketchResult> buildAndRankCollocates(
            Map<String, Long> freqMap,
            long headwordFreq,
            double minLogDice,
            int maxResults,
            Map<String, String> posMap) throws IOException {
        Map<String, Long> collocateCorpusFreqs = prefetchCorpusFrequencies(freqMap.keySet());

        List<WordSketchResult> results = new ArrayList<>();
        for (Map.Entry<String, Long> entry : freqMap.entrySet()) {
            String collocateLemma = entry.getKey();
            long jointFreq = entry.getValue();
            long collocateFreq = collocateCorpusFreqs.getOrDefault(collocateLemma, 0L);

            double logDice = (headwordFreq > 0 && collocateFreq > 0)
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
    // BCQL hit-collection pipeline (used by executeBcqlQuery)
    // -------------------------------------------------------------------------

    /** Per-hit data collected in phase 1 of {@link #executeBcqlQuery}. */
    record HitRecord(String xmlSnippet, String leftText, String matchText, String rightText,
                     @Nullable String collocateLemma, int docId, int start, int end) {}

    /**
     * Full two-phase BCQL query execution: parse → collect hits → score → rank.
     */
    List<CollocateResult> executeBcqlQuery(String bcqlPattern, int maxResults)
            throws IOException {
        try {
            BlackLabIndex idx = this.index;
            TextPattern pattern = nl.inl.blacklab.queryParser.corpusql.CorpusQueryLanguageParser
                    .parse(bcqlPattern, "lemma");
            BLSpanQuery query = pattern.toQuery(QueryInfo.create(idx));

            Hits hits = idx.find(query);
            String headword = CqlUtils.extractHeadword(bcqlPattern);
            long headwordFreq = headword != null ? getTotalFrequency(headword) : 0L;
            int collocatePos = CqlUtils.findLabelTokenIndex(bcqlPattern, 2);
            int sampleSize = (int) Math.min(hits.size(), (long) maxResults * OVER_FETCH_FACTOR); // safe: min ensures result ≤ maxResults * OVER_FETCH_FACTOR

            Map<String, Long> collocateFreqMap = new HashMap<>();
            List<HitRecord> hitRecords = collectHits(hits, sampleSize, collocatePos);
            buildCollocateFrequencyMap(hitRecords, collocateFreqMap);
            List<CollocateResult> scored = scoreHits(hitRecords, collocateFreqMap, headwordFreq);
            return scored.stream()
                    .sorted(Comparator.comparingDouble(CollocateResult::logDice).reversed())
                    .limit(maxResults)
                    .toList();

        } catch (InvalidQuery e) {
            throw new IllegalArgumentException("BCQL parse error: " + e.getMessage(), e);
        }
    }

    /**
     * Phase 1 — hit collection: iterate sample hits and extract text/XML and collocate lemma
     * into a list of {@link HitRecord}s. Frequency counting is a separate concern handled by
     * {@link #buildCollocateFrequencyMap}.
     */
    private List<HitRecord> collectHits(Hits hits, int sampleSize, int collocatePos) {
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

            records.add(new HitRecord(xmlSnippet, leftText, matchText, rightText,
                    collocateLemma, hit.doc(), hit.start(), hit.end()));
        }
        return records;
    }

    /**
     * Phase 1b — frequency counting: accumulates per-collocate co-occurrence counts from
     * a previously collected list of {@link HitRecord}s into {@code freqMapOut}.
     */
    private static void buildCollocateFrequencyMap(List<HitRecord> records, Map<String, Long> freqMapOut) {
        for (HitRecord rec : records) {
            String collocateLemma = rec.collocateLemma();
            if (collocateLemma != null && !collocateLemma.isEmpty()) {
                freqMapOut.merge(collocateLemma.toLowerCase(), 1L, Long::sum);
            }
        }
    }

    /**
     * Phase 2 — score each hit with logDice using corpus frequencies.
     * Frequencies for each unique collocate are pre-fetched once and cached in a local map
     * to avoid redundant {@link #getTotalFrequency} calls for the same lemma.
     * Scores all hit records and returns the full list (unsorted); the caller is responsible for
     * sorting by logDice and limiting to {@code maxResults}.
     */
    private List<CollocateResult> scoreHits(List<HitRecord> records,
            Map<String, Long> collocateFreqMap, long headwordFreq) throws IOException {
        // Pre-compute corpus frequency for each unique collocate to avoid one call per hit.
        Map<String, Long> collocateCorpusFreqs = prefetchCorpusFrequencies(collocateFreqMap.keySet());

        List<CollocateResult> results = new ArrayList<>();

        for (HitRecord rec : records) {
            String collocateLemma = rec.collocateLemma();
            if (collocateLemma == null || collocateLemma.isEmpty()) {
                // Null-collocate hits are SENTENCE_BOUND sentinels (no extractable collocate);
                // they carry no logDice score and must not be mixed into the ranked results.
                continue;
            }

            String key = collocateLemma.toLowerCase();
            long jointFreq = collocateFreqMap.getOrDefault(key, 0L);
            long collocateFreq = collocateCorpusFreqs.getOrDefault(key, 0L);

            double logDice = (headwordFreq > 0 && collocateFreq > 0)
                    ? LogDiceUtils.compute(jointFreq, headwordFreq, collocateFreq) : 0.0;

            String plainText = BlackLabSnippetParser.trimToSentence(
                    rec.leftText(), rec.matchText(), rec.rightText());

            results.add(new CollocateResult(
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

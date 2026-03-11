package pl.marcinmilkowski.word_sketch.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import nl.inl.blacklab.search.BlackLab;
import nl.inl.blacklab.search.BlackLabIndex;
import nl.inl.blacklab.search.Concordance;
import nl.inl.blacklab.search.ConcordanceType;
import nl.inl.blacklab.search.lucene.BLSpanQuery;
import nl.inl.blacklab.search.results.Concordances;
import nl.inl.blacklab.search.results.ContextSize;
import nl.inl.blacklab.search.results.Hit;
import nl.inl.blacklab.search.results.HitGroup;
import nl.inl.blacklab.search.results.HitGroups;
import nl.inl.blacklab.search.results.Hits;
import nl.inl.blacklab.search.results.QueryInfo;
import nl.inl.blacklab.search.results.Results;
import nl.inl.blacklab.searches.SearchHits;
import nl.inl.blacklab.search.textpattern.TextPattern;
import nl.inl.blacklab.search.textpattern.CompleteQuery;
import nl.inl.blacklab.queryParser.contextql.ContextualQueryLanguageParser;
import nl.inl.blacklab.exceptions.InvalidQuery;
import nl.inl.blacklab.search.indexmetadata.AnnotatedField;
import nl.inl.blacklab.search.indexmetadata.Annotation;
import nl.inl.blacklab.search.indexmetadata.AnnotationSensitivity;
import nl.inl.blacklab.search.indexmetadata.MatchSensitivity;
import nl.inl.blacklab.resultproperty.HitProperty;
import nl.inl.blacklab.resultproperty.HitPropertyHitText;
import nl.inl.blacklab.search.TermFrequencyList;

import pl.marcinmilkowski.word_sketch.utils.LogDiceCalculator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Query executor using BlackLab for CoNLL-U dependency tree indexing and querying.
 */
public class BlackLabQueryExecutor implements QueryExecutor {
    private static final Logger logger = LoggerFactory.getLogger(BlackLabQueryExecutor.class);

    /** Over-fetch factor: request 3x as many hits as needed to compensate for scoring discards. */
    private static final int OVER_FETCH_FACTOR = 3;



    private final BlackLabIndex blackLabIndex;
    private final String indexPath;

    public BlackLabQueryExecutor(String indexPath) throws IOException {
        this.indexPath = indexPath;
        try {
            this.blackLabIndex = BlackLab.open(new File(indexPath));
        } catch (Exception e) {
            throw new IOException("Failed to open index: " + e.getMessage(), e);
        }
    }

    @Override
    public List<QueryResults.WordSketchResult> findCollocations(
            String lemma,
            String cqlPattern,
            double minLogDice,
            int maxResults) throws IOException {

        if (lemma == null || lemma.isEmpty()) {
            return Collections.emptyList();
        }

        // Use the provided CQL pattern with the lemma
        // Pattern can be like "[xpos=JJ.*]" or "[lemma=\"%s\"] []{0,5} [xpos=\"JJ.*\"]"
        String bcql;
        if (cqlPattern.contains("%s")) {
            // Pattern has placeholder for lemma
            bcql = String.format(cqlPattern, lemma.toLowerCase());
        } else if (cqlPattern.startsWith("[")) {
            // Simple pattern like "[xpos=JJ.*]" - find lemma followed by pattern
            // BCQL requires proper sequence syntax
            bcql = String.format("\"%s\" %s", lemma.toLowerCase(), cqlPattern);
        } else {
            // Unrecognized pattern format — patterns must contain '%s' or start with '['.
            throw new IllegalArgumentException(
                "Unrecognized CQL pattern format: " + cqlPattern);
        }

        try {
            // BCQL requires the pattern to be a valid sequence
            // For simple attribute constraints, we need to use the proper syntax
            TextPattern pattern = nl.inl.blacklab.queryParser.corpusql.CorpusQueryLanguageParser.parse(bcql, "lemma");
            BLSpanQuery query = pattern.toQuery(QueryInfo.create(blackLabIndex));

            long headwordFreq = getTotalFrequency(lemma);

            // Group by the matched collocate (last token in pattern)
            SearchHits searchHits = blackLabIndex.search().find(query);
            HitProperty groupBy = new HitPropertyHitText(blackLabIndex, MatchSensitivity.INSENSITIVE);
            HitGroups groups = searchHits.groupWithStoredHits(groupBy, Results.NO_LIMIT).execute();

            Map<String, Long> freqMap = new LinkedHashMap<>();
            Map<String, String> posMap = new HashMap<>();

            for (HitGroup group : groups) {
                String identity = group.identity().toString();
                if (identity == null || identity.isEmpty()) {
                    continue;
                }

                // Extract lemma from the matched text (XML format first, plain text fallback)
                String collocateLemma = BlackLabSnippetParser.extractLemmaFromMatch(identity);
                if (collocateLemma == null || collocateLemma.isEmpty()) {
                    // HitPropertyHitText returns plain text (e.g. "empirical test"), not XML.
                    // The collocate is the last whitespace-separated token.
                    String trimmed = identity.trim();
                    int lastSpace = trimmed.lastIndexOf(' ');
                    collocateLemma = lastSpace >= 0 ? trimmed.substring(lastSpace + 1) : trimmed;
                }
                if (collocateLemma.isEmpty()) {
                    continue;
                }

                String key = collocateLemma.toLowerCase();
                freqMap.merge(key, group.size(), Long::sum);
                String pos = BlackLabSnippetParser.extractPosFromMatch(identity);
                if (pos != null) posMap.put(key, pos);
            }

            return buildAndRankCollocates(freqMap, posMap, headwordFreq, minLogDice, maxResults);

        } catch (InvalidQuery e) {
            throw new IOException("BCQL parse error: " + e.getMessage(), e);
        }
    }

    @Override
    public List<QueryResults.ConcordanceResult> executeQuery(String cqlPattern, int maxResults) throws IOException {
        try {
            CompleteQuery cq = ContextualQueryLanguageParser.parse(blackLabIndex, cqlPattern);
            TextPattern tp = cq.pattern();
            if (tp == null) {
                return Collections.emptyList();
            }
            BLSpanQuery query = tp.toQuery(QueryInfo.create(blackLabIndex));
            Hits hits = blackLabIndex.find(query);

            List<QueryResults.ConcordanceResult> results = new ArrayList<>();
            Concordances concordances = hits.concordances(ContextSize.get(5, 5, Integer.MAX_VALUE), ConcordanceType.FORWARD_INDEX);

            for (int i = 0; i < Math.min(hits.size(), maxResults); i++) {
                Hit hit = hits.get(i);
                Concordance conc = concordances.get(hit);
                String[] parts = conc.parts();
                String snippet = parts[0] + parts[1] + parts[2];

                results.add(new QueryResults.SnippetResult(
                    snippet, hit.start(), hit.end(), String.valueOf(hit.doc())));
            }

            return results;

        } catch (InvalidQuery e) {
            throw new IOException("CQL parse error: " + e.getMessage(), e);
        }
    }

    /** Per-hit data collected in phase 1 of {@link #executeBcqlQuery}. */
    private record HitRecord(String xmlSnippet, String leftText, String matchText, String rightText,
                             String collocateLemma, int docId, int start, int end) {}

    @Override
    public List<QueryResults.CollocateResult> executeBcqlQuery(String bcqlPattern, int maxResults) throws IOException {
        try {
            TextPattern pattern = nl.inl.blacklab.queryParser.corpusql.CorpusQueryLanguageParser.parse(bcqlPattern, "lemma");
            BLSpanQuery query = pattern.toQuery(QueryInfo.create(blackLabIndex));

            Hits hits = blackLabIndex.find(query);
            String headword = BlackLabSnippetParser.extractHeadword(bcqlPattern);
            long headwordFreq = headword != null ? getTotalFrequency(headword) : 0L;
            int collocatePos = BlackLabSnippetParser.findLabelPosition(bcqlPattern, 2);
            int sampleSize = (int) Math.min(hits.size(), maxResults * 10L);

            // Phase 1: collect per-hit data and accumulate collocate frequencies
            Map<String, Long> collocateFreqMap = new HashMap<>();
            List<HitRecord> hitRecords = collectHits(hits, sampleSize, collocatePos, collocateFreqMap);

            // Phase 2: score hits with logDice
            List<QueryResults.CollocateResult> scored = scoreHits(hitRecords, collocateFreqMap, headwordFreq, maxResults);

            // Phase 3: rank and limit
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
        Concordances concordances = sample.concordances(ContextSize.get(60, 60, Integer.MAX_VALUE), ConcordanceType.FORWARD_INDEX);

        for (int idx = 0; idx < sampleSize; idx++) {
            Hit hit = sample.get(idx);
            Concordance conc = concordances.get(hit);

            if (conc == null) {
                records.add(new HitRecord("", "", "", "", null, hit.doc(), hit.start(), hit.end()));
                continue;
            }
            String[] parts = conc.parts();
            if (parts == null || parts.length < 3) {
                records.add(new HitRecord("", "", "", "", null, hit.doc(), hit.start(), hit.end()));
                continue;
            }

            String leftXml  = parts[0] != null ? parts[0] : "";
            String matchXml = parts[1] != null ? parts[1] : "";
            String rightXml = parts[2] != null ? parts[2] : "";
            String xmlSnippet = leftXml + matchXml + rightXml;
            String leftText   = BlackLabSnippetParser.extractPlainTextFromXml(BlackLabSnippetParser.trimLeftXmlAtSentence(leftXml));
            String matchText  = BlackLabSnippetParser.extractPlainTextFromXml(matchXml);
            String rightText  = BlackLabSnippetParser.extractPlainTextFromXml(BlackLabSnippetParser.trimRightXmlAtSentence(rightXml));
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
     * Phase 2 — collocate frequency accumulation into scored CollocateResults.
     * Computes logDice for each hit using corpus frequencies.
     */
    private List<QueryResults.CollocateResult> scoreHits(List<HitRecord> records,
            Map<String, Long> collocateFreqMap, long headwordFreq, int maxResults) throws IOException {
        List<QueryResults.CollocateResult> results = new ArrayList<>();
        int limit = Math.min(records.size(), maxResults * OVER_FETCH_FACTOR);

        for (int i = 0; i < limit; i++) {
            HitRecord rec = records.get(i);
            String collocateLemma = rec.collocateLemma();
            // null/empty is valid "no collocate" — do not fall back to "unknown"

            long jointFreq = 0L;
            if (collocateLemma != null && !collocateLemma.isEmpty()) {
                jointFreq = collocateFreqMap.getOrDefault(collocateLemma.toLowerCase(), 0L);
                if (jointFreq == 0L) {
                    logger.warn("Collocate '{}' not found in frequency map — logDice will be 0", collocateLemma);
                }
            }
            long collocateFreq = (collocateLemma != null && !collocateLemma.isEmpty())
                ? getTotalFrequency(collocateLemma) : 0L;

            double logDice = (headwordFreq > 0 && collocateFreq > 0)
                ? LogDiceCalculator.compute(jointFreq, headwordFreq, collocateFreq) : 0.0;

            String plainText = BlackLabSnippetParser.trimToSentence(rec.leftText(), rec.matchText(), rec.rightText());

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
    private static String extractCollocateLemma(String matchXml, int collocatePos) {
        if (collocatePos > 0) {
            String extracted = BlackLabSnippetParser.extractCollocateFromXmlByPosition(matchXml, collocatePos);
            if (extracted != null && !extracted.isEmpty()) return extracted;
            // Fallback: last lemma in match XML
        }
        return BlackLabSnippetParser.extractCollocateLemma(matchXml);
    }


    /**
     * Returns total token frequency for the given lemma in the index.
     * Returns {@code 0L} if the lemma annotation is not found.
     * Propagates {@link RuntimeException} directly and wraps any other checked
     * exception as {@link IOException}.
     *
     * @throws IOException if an unexpected non-runtime failure occurs
     */
    @Override
    public long getTotalFrequency(String lemma) throws IOException {
        try {
            AnnotatedField field = blackLabIndex.mainAnnotatedField();
            Annotation annotation = field.annotation("lemma");
            if (annotation == null) {
                return 0L;
            }
            AnnotationSensitivity sensitivity = annotation.sensitivity(MatchSensitivity.INSENSITIVE);
            TermFrequencyList tfl = blackLabIndex.termFrequencies(sensitivity, null, Set.of(lemma.toLowerCase()));
            return tfl.frequency(lemma.toLowerCase());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unexpected failure retrieving frequency for lemma '" + lemma + "'", e);
        }
    }

    /**
     * Execute a dependency relation query for word sketches.
     * Uses BCQL dependency syntax: "headword" -deprel-> _
     * This finds all words that have the specified dependency relation to the headword.
     * 
     * @param lemma The headword lemma to search for
     * @param deprel The dependency relation (e.g., "nsubj", "obj", "amod")
     * @param headPosConstraint Optional POS constraint for the head (e.g., "VB.*"), or null
     * @param minLogDice Minimum logDice score threshold
     * @param maxResults Maximum number of results to return
     * @return List of collocation results sorted by logDice descending
     */
    @Override
    public List<QueryResults.WordSketchResult> executeDependencyPattern(
            String lemma, 
            String deprel,
            String headPosConstraint,
            double minLogDice, 
            int maxResults) throws IOException {

        if (lemma == null || lemma.isEmpty() || deprel == null) {
            return Collections.emptyList();
        }

        // BCQL dependency syntax: "lemma" -deprel-> _
        // The underscore matches any dependent word
        String bcql;
        if (headPosConstraint != null && !headPosConstraint.isEmpty()) {
            // With POS constraint on head: [lemma="theory" & xpos="NN.*"] -nsubj-> _
            bcql = String.format("[lemma=\"%s\" & xpos=\"%s\"] -%s-> _", 
                                 lemma.toLowerCase(), headPosConstraint, deprel);
        } else {
            // Simple form: "lemma" -deprel-> _
            bcql = String.format("\"%s\" -%s-> _", 
                                 lemma.toLowerCase(), deprel);
        }

        try {
            TextPattern pattern = nl.inl.blacklab.queryParser.corpusql.CorpusQueryLanguageParser.parse(bcql, "lemma");
            BLSpanQuery query = pattern.toQuery(QueryInfo.create(blackLabIndex));

            long headwordFreq = getTotalFrequency(lemma);

            // Group by dependent lemma to get frequencies
            Map<String, Long> freqMap = new HashMap<>();
            Map<String, String> lemmaPosMap = new HashMap<>();

            SearchHits searchHits = blackLabIndex.search().find(query);
            HitProperty groupBy = new HitPropertyHitText(blackLabIndex, MatchSensitivity.INSENSITIVE);
            HitGroups groups = searchHits.groupWithStoredHits(groupBy, Results.NO_LIMIT).execute();

            for (HitGroup group : groups) {
                String identity = group.identity().toString();
                if (identity == null || identity.isEmpty()) continue;
                String dependentLemma = BlackLabSnippetParser.extractCollocateLemma(identity);
                if (dependentLemma != null && !dependentLemma.isEmpty()) {
                    String key = dependentLemma.toLowerCase();
                    freqMap.merge(key, group.size(), Long::sum);
                    String pos = BlackLabSnippetParser.extractPosFromMatch(identity);
                    if (pos != null) {
                        lemmaPosMap.put(key, pos);
                    }
                }
            }

            return buildAndRankCollocates(freqMap, lemmaPosMap, headwordFreq, minLogDice, maxResults);

        } catch (InvalidQuery e) {
            throw new IOException("BCQL parse error: " + e.getMessage(), e);
        }
    }

    /**
     * Execute a surface pattern query for word sketches.
     * Uses BCQL to find collocates matching the pattern.
     * Properly handles labeled capture groups (1: for head, 2: for collocate).
     */
    @Override
    public List<QueryResults.WordSketchResult> executeSurfacePattern(
            String lemma, String bcqlPattern,
            double minLogDice, int maxResults) throws IOException {

        if (lemma == null || lemma.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            TextPattern pattern = nl.inl.blacklab.queryParser.corpusql.CorpusQueryLanguageParser.parse(bcqlPattern, "lemma");

            // Find the position of label "2:" in the pattern
            int collocateLabelPos = BlackLabSnippetParser.findLabelPosition(bcqlPattern, 2);
            BLSpanQuery query = pattern.toQuery(QueryInfo.create(blackLabIndex));

            long headwordFreq = getTotalFrequency(lemma);

            // Build a map of collocate -> frequency by grouping hits
            Map<String, Long> freqMap = new HashMap<>();

            // Use hit groups - group by the matched text to get unique collocates
            SearchHits searchHits = blackLabIndex.search().find(query);
            HitProperty groupBy = new HitPropertyHitText(blackLabIndex, MatchSensitivity.INSENSITIVE);
            HitGroups groups = searchHits.group(groupBy, Results.NO_LIMIT).execute();

            for (HitGroup group : groups) {
                String identity = group.identity().toString();
                if (identity != null && !identity.isEmpty()) {
                    // The group identity is the matched text - extract the collocate from it
                    // Try XML format first, then plain text
                    String collocate = BlackLabSnippetParser.extractLemmaAt(identity, collocateLabelPos);
                    if (collocate == null || collocate.isEmpty()) {
                        // Try plain text extraction - split by whitespace
                        collocate = BlackLabSnippetParser.extractPlainTextTokenAt(identity, collocateLabelPos);
                    }
                    if (collocate != null && !collocate.isEmpty()) {
                        freqMap.merge(collocate.toLowerCase(), group.size(), Long::sum);
                    }
                }
            }

            return buildAndRankCollocates(freqMap, null, headwordFreq, minLogDice, maxResults);

        } catch (InvalidQuery e) {
            throw new IOException("BCQL parse error: " + e.getMessage(), e);
        }
    }

    /**
     * Build and rank collocate results from a frequency map using logDice scoring.
     */
    private List<QueryResults.WordSketchResult> buildAndRankCollocates(
            Map<String, Long> freqMap,
            Map<String, String> posMap,
            long headwordFreq,
            double minLogDice,
            int maxResults) throws IOException {
        List<QueryResults.WordSketchResult> results = new ArrayList<>();
        for (Map.Entry<String, Long> entry : freqMap.entrySet()) {
            String collocateLemma = entry.getKey();
            long jointFreq = entry.getValue();
            long collocateFreq = getTotalFrequency(collocateLemma);

            double logDice = LogDiceCalculator.compute(jointFreq, headwordFreq, collocateFreq);

            if (logDice >= minLogDice) {
                double relFreq = LogDiceCalculator.relativeFrequency(jointFreq, headwordFreq);
                String pos = posMap != null ? posMap.getOrDefault(collocateLemma, QueryResults.WordSketchResult.UNKNOWN_POS) : QueryResults.WordSketchResult.UNKNOWN_POS;
                results.add(new QueryResults.WordSketchResult(
                    collocateLemma, pos, jointFreq, logDice, relFreq, Collections.emptyList()));
            }
        }
        return results.stream()
            .sorted(Comparator.comparingDouble(QueryResults.WordSketchResult::logDice).reversed())
            .limit(maxResults)
            .toList();
    }

    @Override
    public String getExecutorType() {
        return "blacklab";
    }

    @Override
    public void close() throws IOException {
        blackLabIndex.close();
    }
}

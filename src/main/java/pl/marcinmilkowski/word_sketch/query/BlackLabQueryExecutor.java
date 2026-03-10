package pl.marcinmilkowski.word_sketch.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import nl.inl.blacklab.search.BlackLab;
import nl.inl.blacklab.search.BlackLabIndex;
import nl.inl.blacklab.search.Concordance;
import nl.inl.blacklab.search.ConcordanceType;
import nl.inl.blacklab.search.lucene.BLSpanQuery;
import nl.inl.blacklab.search.results.*;
import nl.inl.blacklab.searches.SearchHits;
import nl.inl.blacklab.search.textpattern.TextPattern;
import nl.inl.blacklab.search.textpattern.CompleteQuery;
import nl.inl.blacklab.queryParser.contextql.ContextualQueryLanguageParser;
import nl.inl.blacklab.exceptions.InvalidQuery;
import nl.inl.blacklab.search.indexmetadata.*;
import nl.inl.blacklab.resultproperty.*;
import nl.inl.blacklab.search.TermFrequencyList;

import pl.marcinmilkowski.word_sketch.utils.LogDiceCalculator;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Query executor using BlackLab for CoNLL-U dependency tree indexing and querying.
 */
public class BlackLabQueryExecutor implements QueryExecutor {
    private static final Logger logger = LoggerFactory.getLogger(BlackLabQueryExecutor.class);



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
            String headword,
            String cqlPattern,
            double minLogDice,
            int maxResults) throws IOException {

        if (headword == null || headword.isEmpty()) {
            return Collections.emptyList();
        }

        // Use the provided CQL pattern with the headword
        // Pattern can be like "[xpos=JJ.*]" or "[lemma=\"%s\"] []{0,5} [xpos=\"JJ.*\"]"
        String bcql;
        if (cqlPattern.contains("%s")) {
            // Pattern has placeholder for headword
            bcql = String.format(cqlPattern, headword.toLowerCase());
        } else if (cqlPattern.startsWith("[")) {
            // Simple pattern like "[xpos=JJ.*]" - find headword followed by pattern
            // BCQL requires proper sequence syntax
            bcql = String.format("\"%s\" %s", headword.toLowerCase(), cqlPattern);
        } else {
            // Fallback: just search for headword
            bcql = String.format("\"%s\" []", headword.toLowerCase());
        }

        try {
            // BCQL requires the pattern to be a valid sequence
            // For simple attribute constraints, we need to use the proper syntax
            TextPattern pattern = nl.inl.blacklab.queryParser.corpusql.CorpusQueryLanguageParser.parse(bcql, "lemma");
            BLSpanQuery query = pattern.toQuery(QueryInfo.create(blackLabIndex));

            long headwordFreq = getTotalFrequency(headword);

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
                if (collocateLemma == null || collocateLemma.isEmpty()) {
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

    public List<QueryResults.WordSketchResult> findDependencyCollocations(
            String headword,
            String deprel,
            double minLogDice,
            int maxResults) throws IOException {

        if (headword == null || headword.isEmpty()) {
            return Collections.emptyList();
        }

        // BCQL syntax for dependency: "headword" -deprel-> _
        // This finds all words that have the specified dependency relation to headword
        String bcql = String.format("\"%s\" -%s-> _", headword.toLowerCase(), deprel);

        try {
            TextPattern pattern = nl.inl.blacklab.queryParser.corpusql.CorpusQueryLanguageParser.parse(bcql, "lemma");
            BLSpanQuery query = pattern.toQuery(QueryInfo.create(blackLabIndex));
            
            SearchHits searchHits = blackLabIndex.search().find(query);
            HitProperty groupBy = new HitPropertyHitText(blackLabIndex, MatchSensitivity.SENSITIVE);
            HitGroups groups = searchHits.groupWithStoredHits(groupBy, Results.NO_LIMIT).execute();

            long headwordFreq = getTotalFrequency(headword);
            Map<String, Long> freqMap = new LinkedHashMap<>();
            for (HitGroup group : groups) {
                String collocateLemma = group.identity().toString();
                if (collocateLemma != null && !collocateLemma.isEmpty()) {
                    freqMap.merge(collocateLemma, group.size(), Long::sum);
                }
            }

            return buildAndRankCollocates(freqMap, null, headwordFreq, minLogDice, maxResults);

        } catch (InvalidQuery e) {
            throw new IOException("CQL parse error: " + e.getMessage(), e);
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

                results.add(new QueryResults.ConcordanceResult(
                    snippet, hit.start(), hit.end(), String.valueOf(hit.doc())));
            }

            return results;

        } catch (InvalidQuery e) {
            throw new IOException("CQL parse error: " + e.getMessage(), e);
        }
    }

    @Override
    public List<QueryResults.ConcordanceResult> executeBcqlQuery(String bcqlPattern, int maxResults) throws IOException {
        try {
            // Use CorpusQueryLanguageParser for BCQL (not ContextualQueryLanguageParser)
            TextPattern pattern = nl.inl.blacklab.queryParser.corpusql.CorpusQueryLanguageParser.parse(bcqlPattern, "lemma");
            BLSpanQuery query = pattern.toQuery(QueryInfo.create(blackLabIndex));

            // Use Hits API for concordances
            Hits hits = blackLabIndex.find(query);
            long totalHits = hits.size();

            // Extract headword for logDice calculation
            String headword = BlackLabSnippetParser.extractHeadword(bcqlPattern);
            long headwordFreq = headword != null ? getTotalFrequency(headword) : 0L;

            // Get concordances: 60 tokens each side captures most sentences; post-process to trim at boundaries.
            int collocatePos = BlackLabSnippetParser.findLabelPosition(bcqlPattern, 2);
            int sampleSize = (int) Math.min(totalHits, maxResults * 10L);

            // Single pass: collect per-hit data and build frequency map.
            record HitRecord(String xmlSnippet, String leftText, String matchText, String rightText, String collocateLemma, int docId, int start, int end) {}
            List<HitRecord> hitRecords = new ArrayList<>(sampleSize);
            Map<String, Long> collocateFreqMap = new HashMap<>();

            if (totalHits > 0) {
                Hits sample = hits.window(0L, (long) sampleSize);
                Concordances concordances = sample.concordances(ContextSize.get(60, 60, Integer.MAX_VALUE), ConcordanceType.FORWARD_INDEX);

                for (int idx = 0; idx < sampleSize; idx++) {
                    Hit hit = sample.get(idx);
                    Concordance conc = concordances.get(hit);
                    String leftText = "", matchText = "", rightText = "", xmlSnippet = "";
                    String collocateLemma = null;

                    if (conc != null) {
                        String[] parts = conc.parts();
                        if (parts != null && parts.length >= 3) {
                            String leftXml  = parts[0] != null ? parts[0] : "";
                            String matchXml = parts[1] != null ? parts[1] : "";
                            String rightXml = parts[2] != null ? parts[2] : "";
                            xmlSnippet = leftXml + matchXml + rightXml;
                            leftText   = BlackLabSnippetParser.extractPlainTextFromXml(BlackLabSnippetParser.trimLeftXmlAtSentence(leftXml));
                            matchText  = BlackLabSnippetParser.extractPlainTextFromXml(matchXml);
                            rightText  = BlackLabSnippetParser.extractPlainTextFromXml(BlackLabSnippetParser.trimRightXmlAtSentence(rightXml));

                            // Extract collocate lemma from match XML at the labeled position
                            if (collocatePos > 0) {
                                String extracted = BlackLabSnippetParser.extractCollocateFromXmlByPosition(matchXml, collocatePos);
                                if (extracted != null && !extracted.isEmpty()) {
                                    collocateLemma = extracted;
                                } else {
                                    // Fallback: last lemma in match XML
                                    extracted = BlackLabSnippetParser.extractCollocateFromSnippet(matchXml);
                                    if (extracted != null && !extracted.isEmpty()) {
                                        collocateLemma = extracted;
                                    }
                                }
                            } else {
                                // No labeled position: use last lemma in match XML as best-effort collocate
                                collocateLemma = BlackLabSnippetParser.extractCollocateFromSnippet(matchXml);
                            }
                        }
                    }

                    if (collocateLemma != null && !collocateLemma.isEmpty()) {
                        collocateFreqMap.merge(collocateLemma.toLowerCase(), 1L, Long::sum);
                    }
                    hitRecords.add(new HitRecord(xmlSnippet, leftText, matchText, rightText, collocateLemma, hit.doc(), hit.start(), hit.end()));
                }
            }

            // Second pass: compute logDice and build results using stored plain-text parts.
            List<QueryResults.ConcordanceResult> results = new ArrayList<>();
            int resultLimit = Math.min(hitRecords.size(), maxResults * 3);

            for (int i = 0; i < resultLimit; i++) {
                HitRecord rec = hitRecords.get(i);
                String collocateLemma = rec.collocateLemma();
                // null/empty is valid "no collocate" — do not fall back to "unknown"

                long f_xy = (collocateLemma != null && !collocateLemma.isEmpty())
                    ? collocateFreqMap.getOrDefault(collocateLemma.toLowerCase(), 1L) : 0L;
                long f_y = (collocateLemma != null && !collocateLemma.isEmpty())
                    ? getTotalFrequency(collocateLemma) : 0L;

                double logDice = (headwordFreq > 0 && f_y > 0)
                    ? LogDiceCalculator.compute(f_xy, headwordFreq, f_y) : 0.0;

                String plainText = BlackLabSnippetParser.trimToSentence(rec.leftText(), rec.matchText(), rec.rightText());

                results.add(new QueryResults.ConcordanceResult(
                    plainText, rec.xmlSnippet(), rec.start(), rec.end(), String.valueOf(rec.docId()),
                    collocateLemma, f_xy, logDice));
            }

            // Sort by logDice and limit
            return results.stream()
                .sorted(Comparator.comparingDouble(QueryResults.ConcordanceResult::getLogDice).reversed())
                .limit(maxResults)
                .toList();

        } catch (InvalidQuery e) {
            throw new IOException("BCQL parse error: " + e.getMessage(), e);
        }
    }

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
        } catch (Exception e) {
            return 0L;
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
                String dependentLemma = extractDependentLemmaFromGroup(group);
                if (dependentLemma != null && !dependentLemma.isEmpty()) {
                    freqMap.merge(dependentLemma.toLowerCase(), group.size(), Long::sum);
                    // Store POS if available
                    String pos = extractPosFromGroup(group);
                    if (pos != null) {
                        lemmaPosMap.put(dependentLemma.toLowerCase(), pos);
                    }
                }
            }

            return buildAndRankCollocates(freqMap, lemmaPosMap, headwordFreq, minLogDice, maxResults);

        } catch (InvalidQuery e) {
            throw new IOException("BCQL parse error: " + e.getMessage(), e);
        }
    }

    /**
     * Extract the dependent lemma from a dependency query hit group.
     * The dependent is matched by the underscore in the BCQL pattern.
     * The group identity contains the matched text from which we extract the lemma.
     */
    private String extractDependentLemmaFromGroup(HitGroup group) {
        // Get the group identity (matched text)
        String identity = group.identity().toString();
        if (identity == null || identity.isEmpty()) {
            return null;
        }

        // Extract lemma from XML attributes in the match
        // For dependency queries, the underscore matches the dependent word
        // We want the lemma of that matched word
        java.util.regex.Matcher m = BlackLabSnippetParser.LEMMA_ATTR.matcher(identity);
        String lastLemma = null;
        while (m.find()) {
            lastLemma = m.group(1);
        }
        if (lastLemma != null) {
            return lastLemma.toLowerCase();
        }
        return null;
    }

    /**
     * Extract POS tag from a hit group.
     * The group identity contains the matched text from which we extract POS.
     */
    private String extractPosFromGroup(HitGroup group) {
        String identity = group.identity().toString();
        if (identity == null || identity.isEmpty()) {
            return null;
        }

        // Extract xpos or upos from XML
        java.util.regex.Matcher m = BlackLabSnippetParser.XPOS_ATTR.matcher(identity);
        if (m.find()) {
            return m.group(1);
        }
        // Fallback to upos
        m = BlackLabSnippetParser.UPOS_ATTR.matcher(identity);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Execute a surface pattern query for word sketches.
     * Uses BCQL to find collocates matching the pattern.
     * Properly handles labeled capture groups (1: for head, 2: for collocate).
     */
    public List<QueryResults.WordSketchResult> executeSurfacePattern(
            String lemma, String bcqlPattern,
            int headPosition, int collocatePosition,
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
                    String collocate = BlackLabSnippetParser.extractCollocateFromMatch(identity, collocateLabelPos);
                    if (collocate == null || collocate.isEmpty()) {
                        // Try plain text extraction - split by whitespace
                        collocate = BlackLabSnippetParser.extractCollocateFromPlainText(identity, collocateLabelPos);
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
            long f_xy = entry.getValue();
            long f_y = getTotalFrequency(collocateLemma);

            double logDice = LogDiceCalculator.compute(f_xy, headwordFreq, f_y);

            if (logDice >= minLogDice) {
                double relFreq = LogDiceCalculator.relativeFrequency(f_xy, headwordFreq);
                String pos = posMap != null ? posMap.getOrDefault(collocateLemma, "unknown") : "unknown";
                results.add(new QueryResults.WordSketchResult(
                    collocateLemma, pos, f_xy, logDice, relFreq, Collections.emptyList()));
            }
        }
        return results.stream()
            .sorted(Comparator.comparingDouble(QueryResults.WordSketchResult::getLogDice).reversed())
            .limit(maxResults)
            .toList();
    }

        public long getCorpusSize() throws IOException {
        return 0L;
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

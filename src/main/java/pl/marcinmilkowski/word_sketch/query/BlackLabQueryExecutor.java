package pl.marcinmilkowski.word_sketch.query;

import nl.inl.blacklab.search.BlackLab;
import nl.inl.blacklab.search.BlackLabIndex;
import nl.inl.blacklab.search.Concordance;
import nl.inl.blacklab.search.ConcordanceType;
import nl.inl.blacklab.search.lucene.BLSpanQuery;
import nl.inl.blacklab.search.results.Concordances;
import nl.inl.blacklab.search.results.ContextSize;
import nl.inl.blacklab.search.results.Hit;

import pl.marcinmilkowski.word_sketch.model.QueryResults;
import nl.inl.blacklab.search.results.HitGroup;
import nl.inl.blacklab.search.results.HitGroups;
import nl.inl.blacklab.search.results.Hits;
import nl.inl.blacklab.search.results.QueryInfo;
import nl.inl.blacklab.search.textpattern.TextPattern;
import nl.inl.blacklab.search.textpattern.CompleteQuery;
import nl.inl.blacklab.queryParser.contextql.ContextualQueryLanguageParser;
import nl.inl.blacklab.exceptions.InvalidQuery;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Query executor using BlackLab for CoNLL-U dependency tree indexing and querying.
 */
public class BlackLabQueryExecutor implements QueryExecutor {

    private final BlackLabIndex blackLabIndex;
    private final String indexPath;
    private final CollocateQueryHelper helper;

    public BlackLabQueryExecutor(String indexPath) throws IOException {
        this.indexPath = indexPath;
        try {
            this.blackLabIndex = BlackLab.open(new File(indexPath));
        } catch (Exception e) {
            throw new IOException("Failed to open index: " + e.getMessage(), e);
        }
        this.helper = new CollocateQueryHelper(blackLabIndex);
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

        String bcql = buildBcqlWithLemmaSubstitution(cqlPattern, lemma);

        CollocateQueryHelper.CollocateSearch cs = helper.executeCollocateSearch(bcql, lemma, true);
        long headwordFreq = cs.headwordFreq();
        HitGroups groups = cs.groups();

        Map<String, Long> freqMap = new LinkedHashMap<>();
        Map<String, String> lemmaPosMap = new HashMap<>();

        for (HitGroup group : groups) {
            String identity = group.identity().toString();
            if (identity == null || identity.isEmpty()) {
                continue;
            }

            String collocateLemma = BlackLabSnippetParser.extractLemmaWithFallback(identity);
            if (collocateLemma.isEmpty()) {
                continue;
            }

            String key = collocateLemma.toLowerCase();
            freqMap.merge(key, group.size(), Long::sum);
            String pos = BlackLabSnippetParser.extractPosFromMatch(identity);
            if (pos != null) lemmaPosMap.put(key, pos);
        }

        return helper.buildAndRankCollocates(freqMap, lemmaPosMap, headwordFreq, minLogDice, maxResults);
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

    @Override
    public List<QueryResults.CollocateResult> executeBcqlQuery(String bcqlPattern, int maxResults) throws IOException {
        return helper.executeBcqlQuery(bcqlPattern, maxResults);
    }


    @Override
    public long getTotalFrequency(String lemma) throws IOException {
        return helper.getTotalFrequency(lemma);
    }

    /**
     * Execute a dependency relation query for word sketches.
     * Uses BCQL dependency syntax: "headword" -deprel-> _
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

        String bcql;
        if (headPosConstraint != null && !headPosConstraint.isEmpty()) {
            bcql = String.format("[lemma=\"%s\" & xpos=\"%s\"] -%s-> _",
                                 lemma.toLowerCase(), headPosConstraint, deprel);
        } else {
            bcql = String.format("\"%s\" -%s-> _", lemma.toLowerCase(), deprel);
        }

        CollocateQueryHelper.CollocateSearch cs = helper.executeCollocateSearch(bcql, lemma, true);
        long headwordFreq = cs.headwordFreq();
        HitGroups groups = cs.groups();

        Map<String, Long> freqMap = new HashMap<>();
        Map<String, String> lemmaPosMap = new HashMap<>();

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

        return helper.buildAndRankCollocates(freqMap, lemmaPosMap, headwordFreq, minLogDice, maxResults);
    }

    /**
     * Execute a surface pattern query for word sketches.
     * Properly handles labeled capture groups (1: for head, 2: for collocate).
     */
    @Override
    public List<QueryResults.WordSketchResult> executeSurfacePattern(
            String lemma, String bcqlPattern,
            double minLogDice, int maxResults) throws IOException {

        if (lemma == null || lemma.isEmpty()) {
            return Collections.emptyList();
        }

        int collocateLabelPos = BlackLabSnippetParser.findLabelPosition(bcqlPattern, 2);
        CollocateQueryHelper.CollocateSearch cs = helper.executeCollocateSearch(bcqlPattern, lemma, false);
        long headwordFreq = cs.headwordFreq();
        HitGroups groups = cs.groups();

        Map<String, Long> freqMap = new HashMap<>();

        for (HitGroup group : groups) {
            String identity = group.identity().toString();
            if (identity != null && !identity.isEmpty()) {
                String collocate = BlackLabSnippetParser.extractCollocateFromXmlByPosition(identity, collocateLabelPos);
                if (collocate == null || collocate.isEmpty()) {
                    collocate = BlackLabSnippetParser.extractPlainTextTokenAt(identity, collocateLabelPos);
                }
                if (collocate != null && !collocate.isEmpty()) {
                    freqMap.merge(collocate.toLowerCase(), group.size(), Long::sum);
                }
            }
        }

        return helper.buildAndRankCollocates(freqMap, null, headwordFreq, minLogDice, maxResults);
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
     * Supports {@code %s}-style substitution templates and {@code [constraint]}-prefix patterns.
     */
    private static String buildBcqlWithLemmaSubstitution(String cqlPattern, String lemma) {
        if (cqlPattern.contains("%s")) {
            return String.format(cqlPattern, lemma.toLowerCase());
        } else if (cqlPattern.startsWith("[")) {
            return String.format("\"%s\" %s", lemma.toLowerCase(), cqlPattern);
        } else {
            throw new IllegalArgumentException("Unrecognized CQL pattern format: " + cqlPattern);
        }
    }
}

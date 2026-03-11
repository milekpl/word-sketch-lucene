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

import pl.marcinmilkowski.word_sketch.utils.CqlUtils;

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
    private final CollocateQueryHelper collocateQueryHelper;

    public BlackLabQueryExecutor(String indexPath) throws IOException {
        this.indexPath = indexPath;
        try {
            this.blackLabIndex = BlackLab.open(new File(indexPath));
        } catch (Exception e) {
            throw new IOException("Failed to open index: " + e.getMessage(), e);
        }
        this.collocateQueryHelper = new CollocateQueryHelper(blackLabIndex);
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

        CollocateQueryHelper.CollocateSearch collocateSearch = collocateQueryHelper.executeCollocateSearch(bcql, lemma, true);
        long headwordFreq = collocateSearch.headwordFreq();
        HitGroups groups = collocateSearch.groups();

        Map<String, Long> freqMap = new LinkedHashMap<>();
        Map<String, String> lemmaPosMap = new HashMap<>();

        collectFrequenciesFromGroups(groups, identity -> {
            String collocateLemma = BlackLabSnippetParser.extractLemmaWithFallback(identity);
            return collocateLemma.isEmpty() ? null : collocateLemma;
        }, freqMap, lemmaPosMap);

        return collocateQueryHelper.buildAndRankCollocates(freqMap, lemmaPosMap, headwordFreq, minLogDice, maxResults);
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
        return collocateQueryHelper.executeBcqlQuery(bcqlPattern, maxResults);
    }


    @Override
    public long getTotalFrequency(String lemma) throws IOException {
        return collocateQueryHelper.getTotalFrequency(lemma);
    }

    /**
     * Execute a dependency relation query without a head POS constraint.
     * Uses BCQL dependency syntax: "headword" -deprel-> _
     */
    @Override
    public List<QueryResults.WordSketchResult> executeDependencyPattern(
            String lemma,
            String deprel,
            double minLogDice,
            int maxResults) throws IOException {

        if (lemma == null || lemma.isEmpty() || deprel == null) {
            return Collections.emptyList();
        }

        String bcql = String.format("\"%s\" -%s-> _", CqlUtils.escapeForRegex(lemma.toLowerCase()), deprel);
        return runDependencyQuery(bcql, lemma, minLogDice, maxResults);
    }

    /**
     * Execute a dependency relation query with a head POS constraint.
     * Uses BCQL dependency syntax: [lemma="headword" & xpos="POS"] -deprel-> _
     */
    @Override
    public List<QueryResults.WordSketchResult> executeDependencyPatternWithPos(
            String lemma,
            String deprel,
            String headPosConstraint,
            double minLogDice,
            int maxResults) throws IOException {

        if (lemma == null || lemma.isEmpty() || deprel == null) {
            return Collections.emptyList();
        }

        String bcql = String.format("[lemma=\"%s\" & xpos=\"%s\"] -%s-> _",
                                    CqlUtils.escapeForRegex(lemma.toLowerCase()), headPosConstraint, deprel);
        return runDependencyQuery(bcql, lemma, minLogDice, maxResults);
    }

    private List<QueryResults.WordSketchResult> runDependencyQuery(
            String bcql, String lemma, double minLogDice, int maxResults) throws IOException {
        CollocateQueryHelper.CollocateSearch collocateSearch = collocateQueryHelper.executeCollocateSearch(bcql, lemma, true);
        long headwordFreq = collocateSearch.headwordFreq();
        HitGroups groups = collocateSearch.groups();

        Map<String, Long> freqMap = new HashMap<>();
        Map<String, String> lemmaPosMap = new HashMap<>();

        collectFrequenciesFromGroups(groups,
            identity -> BlackLabSnippetParser.extractCollocateLemma(identity),
            freqMap, lemmaPosMap);

        return collocateQueryHelper.buildAndRankCollocates(freqMap, lemmaPosMap, headwordFreq, minLogDice, maxResults);
    }

    /**
     * Iterates over HitGroups and populates frequency and POS maps using the provided lemma extractor.
     * Groups with empty identity or null/empty extracted lemmas are skipped.
     */
    private void collectFrequenciesFromGroups(
            HitGroups groups,
            java.util.function.Function<String, String> lemmaExtractor,
            Map<String, Long> freqMap,
            Map<String, String> lemmaPosMap) {
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
        CollocateQueryHelper.CollocateSearch collocateSearch = collocateQueryHelper.executeCollocateSearch(bcqlPattern, lemma, false);
        long headwordFreq = collocateSearch.headwordFreq();
        HitGroups groups = collocateSearch.groups();

        Map<String, Long> freqMap = new HashMap<>();

        for (HitGroup group : groups) {
            String identity = group.identity().toString();
            if (!identity.isEmpty()) {
                String collocate = BlackLabSnippetParser.extractCollocateFromXmlByPosition(identity, collocateLabelPos);
                if (collocate == null || collocate.isEmpty()) {
                    collocate = BlackLabSnippetParser.extractPlainTextTokenAt(identity, collocateLabelPos);
                }
                if (collocate != null && !collocate.isEmpty()) {
                    freqMap.merge(collocate.toLowerCase(), group.size(), Long::sum);
                }
            }
        }

        return collocateQueryHelper.buildAndRankCollocates(freqMap, null, headwordFreq, minLogDice, maxResults);
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
    private static String buildBcqlWithLemmaSubstitution(String cqlPattern, String lemma) {
        if (cqlPattern.startsWith("[")) {
            return String.format("\"%s\" %s", CqlUtils.escapeForRegex(lemma.toLowerCase()), cqlPattern);
        } else {
            throw new IllegalArgumentException("Unrecognized CQL pattern format: " + cqlPattern);
        }
    }
}

package pl.marcinmilkowski.word_sketch.query;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.grammar.CQLParser;
import pl.marcinmilkowski.word_sketch.grammar.CQLPattern;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.SentenceDocument;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.StatisticsReader;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.TermStatistics;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.TokenSequenceCodec;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.CollocationEntry;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.Collocation;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.CollocationsReader;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Query executor for hybrid sentence-per-document index.
 * 
 * Key differences from legacy token-per-document approach:
 * - One Lucene document per sentence (not per token)
 * - Tokens stored in BinaryDocValues for O(1) position access
 * - Uses SpanQueries for positional matching within sentences
 * - Pre-computed term statistics for fast frequency lookups
 * 
 * Performance benefits:
 * - 10-100x fewer Lucene docs to search
 * - No need for batch sentence loading - tokens are in DocValues
 * - SpanNear/SpanOr for native positional matching
 * 
 * Algorithm modes:
 * - PRECOMPUTED: Fast O(1) lookup of precomputed collocations (recommended)
 * - SAMPLE_SCAN: Sample sentences, scan tokens (original algorithm, deprecated)
 * - SPAN_COUNT: Iterate candidates, count via SpanNear (inverted index direct, deprecated)
 */
public class HybridQueryExecutor implements QueryExecutor {

    /**
     * Algorithm mode for finding collocations.
     */
    public enum Algorithm {
        /** Precomputed collocations with O(1) lookup. Recommended for production use. */
        PRECOMPUTED,
        /** Sample sentences, scan tokens within. O(S·H·L) complexity. @deprecated Use PRECOMPUTED instead. */
        @Deprecated(since = "2.0", forRemoval = true)
        SAMPLE_SCAN,
        /** Iterate candidates from stats, count via SpanNear. O(C·log N) complexity. @deprecated Use PRECOMPUTED instead. */
        @Deprecated(since = "2.0", forRemoval = true)
        SPAN_COUNT
    }

    private static final Logger logger = LoggerFactory.getLogger(HybridQueryExecutor.class);

    private final IndexReader reader;
    private final IndexSearcher searcher;
    private final CQLToLuceneCompiler compiler;
    private final StatisticsReader statsReader;
    private final String statsPath;
    private final String statsSource;
    private CollocationsReader collocationsReader;

    private final LongAdder statsLookups = new LongAdder();
    private final LongAdder statsMisses = new LongAdder();
    private final LongAdder indexLookups = new LongAdder();

    private final ThreadLocal<QueryReport> lastQueryReport = new ThreadLocal<>();
    
    private int maxSampleSize = 10_000;
    private Algorithm algorithm = Algorithm.PRECOMPUTED;
    private int minCandidateFrequency = 2;  // Minimum frequency for span-based candidates

    /**
     * Create a HybridQueryExecutor for a hybrid index.
     * 
     * @param indexPath Path to the hybrid index directory
     * @throws IOException if index cannot be opened
     */
    public HybridQueryExecutor(String indexPath) throws IOException {
        this(indexPath, resolveStatsPath(indexPath));
    }
    
    private static String resolveStatsPath(String indexPath) {
        String binPath = indexPath + "/stats.bin";
        if (java.nio.file.Files.exists(java.nio.file.Paths.get(binPath))) {
            return binPath;
        }
        return indexPath + "/stats.tsv";
    }

    private static String resolveStatsSource(String path, StatisticsReader reader) {
        if (reader == null) {
            return "none";
        }
        if (path == null) {
            return "unknown";
        }
        String lower = path.toLowerCase();
        if (lower.endsWith(".bin")) {
            return "binary";
        }
        if (lower.endsWith(".tsv")) {
            return "tsv";
        }
        return "unknown";
    }

    /**
     * Create a HybridQueryExecutor with explicit statistics file path.
     * 
     * @param indexPath Path to the hybrid index directory
     * @param statsPath Path to the statistics file (.bin or .tsv)
     * @throws IOException if index or stats cannot be opened
     */
    public HybridQueryExecutor(String indexPath, String statsPath) throws IOException {
        Path path = Paths.get(indexPath);
        this.reader = DirectoryReader.open(MMapDirectory.open(path));
        this.searcher = new IndexSearcher(reader);
        this.compiler = new CQLToLuceneCompiler();
        this.statsPath = statsPath;
        
        // Try to load statistics
        StatisticsReader tempStats = null;
        try {
            tempStats = new StatisticsReader(statsPath);
            logger.info("Loaded term statistics from: {}", statsPath);
        } catch (IOException e) {
            logger.warn("No statistics file found at {}. Will use index for frequency lookups.", statsPath);
        }
        this.statsReader = tempStats;
        this.statsSource = resolveStatsSource(statsPath, statsReader);
        
        // Try to load precomputed collocations
        String collocationsPath = indexPath + "/collocations.bin";
        CollocationsReader tempCollocations = null;
        try {
            if (java.nio.file.Files.exists(java.nio.file.Paths.get(collocationsPath))) {
                tempCollocations = new CollocationsReader(collocationsPath);
                logger.info("Loaded precomputed collocations: {} entries", tempCollocations.getEntryCount());
            }
        } catch (IOException e) {
            logger.warn("Failed to load precomputed collocations: {}", e.getMessage());
        }
        this.collocationsReader = tempCollocations;
        
        logger.info("HybridQueryExecutor initialized with {} sentences", reader.numDocs());
    }

    @Override
    public String getExecutorType() {
        return "hybrid";
    }

    public void setMaxSampleSize(int maxSampleSize) {
        this.maxSampleSize = maxSampleSize;
    }

    public int getMaxSampleSize() {
        return maxSampleSize;
    }

    public void setAlgorithm(Algorithm algorithm) {
        this.algorithm = algorithm;
        logger.info("Algorithm set to: {}", algorithm);
    }

    public Algorithm getAlgorithm() {
        return algorithm;
    }

    public void setMinCandidateFrequency(int minFreq) {
        this.minCandidateFrequency = minFreq;
    }

    public int getMinCandidateFrequency() {
        return minCandidateFrequency;
    }

    @Override
    public List<WordSketchQueryExecutor.WordSketchResult> findCollocations(
            String headword, String cqlPattern, double minLogDice, int maxResults) throws IOException {
        
        return switch (algorithm) {
            case SPAN_COUNT -> findCollocationsSpanBased(headword, cqlPattern, minLogDice, maxResults);
            case SAMPLE_SCAN -> findCollocationsSampleScan(headword, cqlPattern, minLogDice, maxResults);
            case PRECOMPUTED -> findCollocationsPrecomputed(headword, cqlPattern, minLogDice, maxResults);
        };
    }

    @Override
    public List<WordSketchQueryExecutor.WordSketchResult> findGrammaticalRelation(
            String headword, RelationType relType, double minLogDice, int maxResults) throws IOException {
        // For grammatical relations, always use SAMPLE_SCAN to handle multi-token patterns
        return findGrammaticalRelationScan(headword, relType, minLogDice, maxResults);
    }

    /**
     * Find grammatical relations by scanning sentences matching a multi-token pattern.
     * Extracts the target token (last token in pattern) and calculates logDice.
     */
    private List<WordSketchQueryExecutor.WordSketchResult> findGrammaticalRelationScan(
            String headword, RelationType relType, double minLogDice, int maxResults) throws IOException {
        
        if (headword == null || headword.isEmpty()) {
            return Collections.emptyList();
        }
        
        headword = headword.toLowerCase();
        long headwordFreq = getTotalFrequency(headword);
        if (headwordFreq == 0) {
            logger.debug("Headword '{}' not found", headword);
            return Collections.emptyList();
        }
        
        String cqlPattern = relType.getFullPattern(headword);
        logger.info("findGrammaticalRelation({}, {}): pattern={}", headword, relType, cqlPattern);
        
        // Parse the full CQL pattern
        CQLPattern pattern = new CQLParser().parse(cqlPattern);
        
        // Extract target constraints (the collocate we're looking for - typically last token)
        List<CQLPattern.Constraint> targetConstraints = extractLastTokenConstraints(pattern);
        if (targetConstraints.isEmpty()) {
            logger.warn("Could not extract target constraints from pattern: {}", cqlPattern);
            return Collections.emptyList();
        }
        
        // Compile and search
        SpanQuery query = compiler.compile(pattern);
        
        int sampleSize = Math.min(maxSampleSize, 50_000);
        TopDocs topDocs = searcher.search(query, sampleSize);
        
        // Use scoreDocs.length as a proxy for total hits in sample
        int totalHits = topDocs.scoreDocs.length;
        double scaleFactor = 1.0; // No scaling for now since we search directly by pattern
        
        logger.info("Pattern matched {} sentences", totalHits);
        
        // Process matches: extract target tokens
        Map<String, Long> lemmaFreqs = new HashMap<>();
        Map<String, String> lemmaPos = new HashMap<>();
        
        for (ScoreDoc hit : topDocs.scoreDocs) {
            List<SentenceDocument.Token> tokens = loadTokensFromDoc(hit.doc);
            if (tokens.isEmpty()) continue;
            
            // Find headword positions
            List<Integer> headwordPositions = new ArrayList<>();
            for (SentenceDocument.Token token : tokens) {
                if (headword.equalsIgnoreCase(token.lemma())) {
                    headwordPositions.add(token.position());
                }
            }
            
            // For each headword, look for copula + adjective pattern
            for (int hwPos : headwordPositions) {
                // Scan forward for copula verbs, then adjective
                for (int i = hwPos + 1; i < tokens.size() && i < hwPos + 8; i++) {
                    SentenceDocument.Token tok = tokens.get(i);
                    String lemma = tok.lemma() != null ? tok.lemma().toLowerCase() : "";
                    String tag = tok.tag() != null ? tok.tag() : "";
                    
                    // Check if this token matches target constraints
                    if (matchesConstraints(lemma, tag, targetConstraints)) {
                        lemmaFreqs.merge(lemma, 1L, Long::sum);
                        lemmaPos.putIfAbsent(lemma, tag.toUpperCase());
                    }
                }
            }
        }
        
        // Calculate logDice and filter
        List<WordSketchQueryExecutor.WordSketchResult> results = new ArrayList<>();
        
        for (Map.Entry<String, Long> entry : lemmaFreqs.entrySet()) {
            String lemma = entry.getKey();
            long cooccur = (long) (entry.getValue() * scaleFactor);
            long collocFreq = getTotalFrequency(lemma);
            if (collocFreq == 0) collocFreq = 1;
            
            double logDice = calculateLogDice(cooccur, headwordFreq, collocFreq);
            
            if (logDice >= minLogDice) {
                String pos = lemmaPos.getOrDefault(lemma, "");
                results.add(new WordSketchQueryExecutor.WordSketchResult(
                    lemma, pos, cooccur, logDice, 0.0, List.of()
                ));
            }
        }
        
        // Sort by logDice and limit
        results.sort((a, b) -> Double.compare(b.getLogDice(), a.getLogDice()));
        if (results.size() > maxResults) {
            results = results.subList(0, maxResults);
        }
        
        logger.info("findGrammaticalRelation: found {} collocates (filtered to {})", 
            lemmaFreqs.size(), results.size());
        
        return results;
    }

    /**
     * Extract constraints from the last token in a CQL pattern.
     */
    private List<CQLPattern.Constraint> extractLastTokenConstraints(CQLPattern pattern) {
        List<CQLPattern.PatternElement> elements = pattern.getElements();
        if (elements == null || elements.isEmpty()) {
            return Collections.emptyList();
        }
        // Get the last element's constraint
        CQLPattern.PatternElement lastElement = elements.get(elements.size() - 1);
        CQLPattern.Constraint constraint = lastElement.getConstraint();
        if (constraint == null) {
            return Collections.emptyList();
        }
        return List.of(constraint);
    }

    @Override
    public List<WordSketchQueryExecutor.ConcordanceResult> executeQuery(String cqlPattern, int maxResults) throws IOException {
        CQLPattern pattern = new CQLParser().parse(cqlPattern);
        SpanQuery query = compiler.compile(pattern);

        TopDocs topDocs = searcher.search(query, maxResults);
        ScoreDoc[] hits = topDocs.scoreDocs;

        List<WordSketchQueryExecutor.ConcordanceResult> results = new ArrayList<>();

        for (ScoreDoc hit : hits) {
            Document doc = searcher.storedFields().document(hit.doc);
            results.add(new WordSketchQueryExecutor.ConcordanceResult(
                doc.get("sentence"),
                doc.get("lemma"),
                doc.get("tag"),
                doc.get("word"),
                doc.get("start_offset") != null ? Integer.parseInt(doc.get("start_offset")) : 0,
                doc.get("end_offset") != null ? Integer.parseInt(doc.get("end_offset")) : 0
            ));
        }

        return results;
    }

    /**
     * Original algorithm: sample sentences, scan tokens within.
     * Complexity: O(S·H·L) where S=sample size, H=headword occurrences, L=tokens per sentence.
     */
    private List<WordSketchQueryExecutor.WordSketchResult> findCollocationsSampleScan(
            String headword, String cqlPattern, double minLogDice, int maxResults) throws IOException {

        if (headword == null || headword.isEmpty()) {
            return Collections.emptyList();
        }

        long startTime = System.currentTimeMillis();
        long t0 = System.nanoTime();

        // Get headword frequency from statistics or index
        long headwordFreq = getTotalFrequency(headword);
        if (headwordFreq == 0) {
            logger.debug("Headword '{}' not found in corpus", headword);
            return Collections.emptyList();
        }

        // Parse CQL pattern
        CQLPattern collocatePattern = new CQLParser().parse(cqlPattern);
        List<CQLPattern.Constraint> collocateConstraints = extractConstraints(collocatePattern);
        int maxDist = extractMaxDistance(collocatePattern);
        if (maxDist <= 0) maxDist = 3;

        // Find sentences containing the headword
        Query headwordQuery = new TermQuery(new Term("lemma", headword.toLowerCase()));
        
        // Count total hits first
        long countStart = System.nanoTime();
        TotalHitCountCollector countCollector = new TotalHitCountCollector();
        searcher.search(headwordQuery, countCollector);
        int totalSentences = countCollector.getTotalHits();
        long countMs = (System.nanoTime() - countStart) / 1_000_000;

        if (totalSentences == 0) {
            logger.debug("No sentences found containing '{}'", headword);
            return Collections.emptyList();
        }

        // Determine sample size
        int sampleSize = (maxSampleSize == 0 || totalSentences <= maxSampleSize) 
            ? totalSentences : maxSampleSize;
        double scaleFactor = (double) totalSentences / sampleSize;

        logger.info("'{}': found {} sentences, processing {} (scale: {}x)",
            headword, totalSentences, sampleSize, String.format("%.1f", scaleFactor));

        // Get sample of sentences
        long searchStart = System.nanoTime();
        TopDocs topDocs = searcher.search(headwordQuery, sampleSize);
        long searchMs = (System.nanoTime() - searchStart) / 1_000_000;

        // Process sentences and collect collocations
        Map<String, Long> lemmaFreqs = new HashMap<>();
        Map<String, String> lemmaPos = new HashMap<>();
        Map<String, List<String>> examples = new HashMap<>();
        long collocateCount = 0;

        // Get DocValues reader for tokens
        var storedFields = searcher.storedFields();

        long tokenDecodeNs = 0;
        long matchNs = 0;
        long exampleNs = 0;

        for (ScoreDoc hit : topDocs.scoreDocs) {
            int docId = hit.doc;
            
            // Load tokens from DocValues
            long decodeStart = System.nanoTime();
            List<SentenceDocument.Token> tokens = loadTokensFromDoc(docId);
            tokenDecodeNs += System.nanoTime() - decodeStart;
            if (tokens.isEmpty()) continue;

            // Load text once per sentence for example collection
            String sentenceText = null;
            
            // Find headword positions in this sentence
            List<Integer> headwordPositions = new ArrayList<>();
            for (SentenceDocument.Token token : tokens) {
                if (headword.equalsIgnoreCase(token.lemma())) {
                    headwordPositions.add(token.position());
                }
            }

            // Find collocates within distance of each headword position
            long matchStart = System.nanoTime();
            for (int hwPos : headwordPositions) {
                for (SentenceDocument.Token token : tokens) {
                    int dist = Math.abs(token.position() - hwPos);
                    if (dist == 0 || dist > maxDist) continue;

                    String lemma = token.lemma();
                    String tag = token.tag();

                    if (lemma == null || lemma.equalsIgnoreCase(headword)) continue;

                    // Check CQL constraints
                    if (!matchesConstraints(lemma, tag, collocateConstraints)) continue;

                    collocateCount++;
                    String key = lemma.toLowerCase();

                    lemmaFreqs.merge(key, 1L, Long::sum);
                    lemmaPos.putIfAbsent(key, tag != null ? tag.toUpperCase() : "");

                    // Collect examples (load text lazily, once per sentence)
                    if (!examples.containsKey(key) || examples.get(key).size() < 3) {
                        if (sentenceText == null) {
                            long exampleStart = System.nanoTime();
                            try {
                                Document doc = storedFields.document(docId, Set.of("text"));
                                sentenceText = doc.get("text");
                            } catch (IOException e) {
                                sentenceText = ""; // Mark as attempted
                            }
                            exampleNs += System.nanoTime() - exampleStart;
                        }
                        if (sentenceText != null && !sentenceText.isEmpty()) {
                            examples.computeIfAbsent(key, k -> new ArrayList<>());
                            if (examples.get(key).size() < 3) {
                                examples.get(key).add(sentenceText);
                            }
                        }
                    }
                }
            }
            matchNs += System.nanoTime() - matchStart;
        }

        logger.debug("Processed {} collocations, found {} unique collocates",
            collocateCount, lemmaFreqs.size());

        // Calculate logDice scores
        long scoreStart = System.nanoTime();
        List<WordSketchQueryExecutor.WordSketchResult> results = new ArrayList<>();
        long totalMatches = Math.max(1, collocateCount);

        for (Map.Entry<String, Long> entry : lemmaFreqs.entrySet()) {
            String lemma = entry.getKey();
            long sampleFreq = entry.getValue();
            long estimatedFreq = Math.round(sampleFreq * scaleFactor);

            long collocateTotalFreq = getTotalFrequency(lemma);
            if (collocateTotalFreq == 0) collocateTotalFreq = 1;

            double logDice = calculateLogDice(estimatedFreq, headwordFreq, collocateTotalFreq);

            if (logDice >= minLogDice || minLogDice == 0) {
                double relFreq = (double) sampleFreq / totalMatches;
                results.add(new WordSketchQueryExecutor.WordSketchResult(
                    lemma,
                    lemmaPos.getOrDefault(lemma, ""),
                    estimatedFreq,
                    logDice,
                    relFreq,
                    examples.getOrDefault(lemma, Collections.emptyList())
                ));
            }
        }

        results.sort((a, b) -> Double.compare(b.getLogDice(), a.getLogDice()));

        long scoreMs = (System.nanoTime() - scoreStart) / 1_000_000;
        long totalMs = (System.nanoTime() - t0) / 1_000_000;

        QueryReport report = new QueryReport(
            headword,
            headwordFreq,
            totalSentences,
            sampleSize,
            scaleFactor,
            collocateCount,
            lemmaFreqs.size(),
            countMs,
            searchMs,
            tokenDecodeNs / 1_000_000,
            matchNs / 1_000_000,
            exampleNs / 1_000_000,
            scoreMs,
            totalMs
        );
        lastQueryReport.set(report);

        double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
        logger.info("Query completed in {}s, returned {} results", String.format("%.2f", elapsed), results.size());

        return results.subList(0, Math.min(maxResults, results.size()));
    }

    /**
     * Span-based algorithm: iterate candidates from stats, count via SpanNear.
     * Complexity: O(C·log N) where C=candidate count, N=corpus size.
     * 
     * Key insight: Instead of scanning tokens in sampled sentences, we:
     * 1. Get all candidate lemmas from stats.bin
     * 2. For each candidate, build SpanNear(headword, candidate, distance)
     * 3. Count matches directly via inverted index
     * 4. Calculate logDice from counts
     * 
     * This is much faster for high-frequency headwords because we never
     * enumerate individual token positions - Lucene does the counting.
     * 
     * Note on POS ambiguity: Since lemmas can have multiple POS tags
     * (e.g., "run" as noun/verb), this counts ALL cooccurrences regardless
     * of POS. This is an approximation, but ranking is preserved because
     * top collocates are dominated by correct POS usage in context.
     */
    private List<WordSketchQueryExecutor.WordSketchResult> findCollocationsSpanBased(
            String headword, String cqlPattern, double minLogDice, int maxResults) throws IOException {

        if (headword == null || headword.isEmpty()) {
            return Collections.emptyList();
        }

        if (statsReader == null) {
            logger.warn("SPAN_COUNT algorithm requires stats.bin - falling back to SAMPLE_SCAN");
            return findCollocationsSampleScan(headword, cqlPattern, minLogDice, maxResults);
        }

        long t0 = System.nanoTime();
        String headwordLower = headword.toLowerCase();

        // Get headword frequency
        long headwordFreq = getTotalFrequency(headword);
        if (headwordFreq == 0) {
            logger.debug("Headword '{}' not found in corpus", headword);
            return Collections.emptyList();
        }

        // Parse CQL pattern to get max distance
        CQLPattern collocatePattern = new CQLParser().parse(cqlPattern);
        int maxDist = extractMaxDistanceFromPattern(collocatePattern);
        if (maxDist <= 0) maxDist = 5;  // Default window

        logger.info("SPAN_COUNT: headword='{}' freq={}, maxDist={}", headword, headwordFreq, maxDist);

        // Get candidate lemmas (filtered by minimum frequency to reduce candidates)
        long candidateStart = System.nanoTime();
        List<String> candidates = statsReader.getLemmasByMinFrequency(minCandidateFrequency);
        long candidateMs = (System.nanoTime() - candidateStart) / 1_000_000;
        
        logger.info("SPAN_COUNT: {} candidates with freq >= {}", candidates.size(), minCandidateFrequency);

        // Build headword span query
        SpanTermQuery headwordSpan = new SpanTermQuery(new Term("lemma", headwordLower));

        // Count cooccurrences for each candidate via SpanNear
        long spanStart = System.nanoTime();
        List<WordSketchQueryExecutor.WordSketchResult> results = new ArrayList<>();
        int candidatesProcessed = 0;
        int candidatesMatched = 0;

        for (String candidate : candidates) {
            if (candidate.equals(headwordLower)) continue;  // Skip self

            candidatesProcessed++;

            // Build SpanNear query: headword within maxDist of candidate
            SpanTermQuery candidateSpan = new SpanTermQuery(new Term("lemma", candidate));
            SpanNearQuery spanNear = new SpanNearQuery(
                new SpanQuery[]{headwordSpan, candidateSpan},
                maxDist,
                false  // Don't require order - collocate can be before or after
            );

            // Count matches using Lucene's inverted index
            int cooccurrenceCount = searcher.count(spanNear);

            if (cooccurrenceCount > 0) {
                candidatesMatched++;

                long candidateFreq = statsReader.getFrequency(candidate);
                double logDice = calculateLogDice(cooccurrenceCount, headwordFreq, candidateFreq);

                if (logDice >= minLogDice || minLogDice == 0) {
                    // Get POS from stats
                    TermStatistics stats = statsReader.getStatistics(candidate);
                    String pos = stats != null ? getMostFrequentPos(stats) : "";

                    results.add(new WordSketchQueryExecutor.WordSketchResult(
                        candidate,
                        pos,
                        cooccurrenceCount,
                        logDice,
                        0.0,  // relFreq not meaningful here
                        Collections.emptyList()  // Examples not collected in this mode
                    ));
                }
            }
        }

        long spanMs = (System.nanoTime() - spanStart) / 1_000_000;

        // Sort by logDice descending
        results.sort((a, b) -> Double.compare(b.getLogDice(), a.getLogDice()));

        long totalMs = (System.nanoTime() - t0) / 1_000_000;

        // Create report
        QueryReport report = new QueryReport(
            headword,
            headwordFreq,
            0,  // totalSentences not used in this mode
            candidatesProcessed,
            1.0,  // scaleFactor = 1 (no sampling)
            candidatesMatched,
            results.size(),
            candidateMs,
            spanMs,
            0,  // tokenDecodeMs
            0,  // matchMs
            0,  // exampleMs
            0,  // scoreMs
            totalMs
        );
        lastQueryReport.set(report);

        logger.info("SPAN_COUNT: processed {} candidates, {} matched, {} results in {}ms",
            candidatesProcessed, candidatesMatched, results.size(), totalMs);

        return results.subList(0, Math.min(maxResults, results.size()));
    }

    /**
     * Extract maximum distance from CQL pattern by summing all repetition ranges.
     * For pattern like: VERB ADVERB{0,2} DET{0,1} NOUN
     * Returns: 0 + 2 + 1 + 0 = 3 (plus 1 for each position = 7 total)
     */
    private int extractMaxDistanceFromPattern(CQLPattern pattern) {
        int maxDist = 0;
        for (CQLPattern.PatternElement elem : pattern.getElements()) {
            // Each element contributes at least 1 position
            maxDist += 1;
            // Plus any repetition allowance
            if (elem.getMaxRepetition() > 1) {
                maxDist += (elem.getMaxRepetition() - 1);
            }
            // Plus any explicit distance (but not if it's Integer.MAX_VALUE)
            if (elem.getMaxDistance() > 0 && elem.getMaxDistance() < Integer.MAX_VALUE) {
                maxDist += elem.getMaxDistance();
            }
        }
        // Cap at reasonable limit for SpanNear (default 50)
        return Math.min(maxDist, 50);
    }

    /**
     * Get the most frequent POS tag for a lemma from statistics.
     */
    private String getMostFrequentPos(TermStatistics stats) {
        if (stats.posDistribution() == null || stats.posDistribution().isEmpty()) {
            return "";
        }
        return stats.posDistribution().entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("");
    }

    /**
     * Load tokens from DocValues for a document.
     */
    private List<SentenceDocument.Token> loadTokensFromDoc(int docId) throws IOException {
        var leafReaders = reader.leaves();
        
        for (var leafContext : leafReaders) {
            int localDocId = docId - leafContext.docBase;
            if (localDocId >= 0 && localDocId < leafContext.reader().maxDoc()) {
                var binaryDocValues = leafContext.reader().getBinaryDocValues("tokens");
                if (binaryDocValues != null && binaryDocValues.advanceExact(localDocId)) {
                    BytesRef bytesRef = binaryDocValues.binaryValue();
                    return TokenSequenceCodec.decode(bytesRef);
                }
            }
        }
        
        return Collections.emptyList();
    }

    @Override
    public long getTotalFrequency(String lemma) throws IOException {
        if (lemma == null) return 0;
        
        String normalized = lemma.toLowerCase();
        
        // Use pre-computed statistics if available
        if (statsReader != null) {
            statsLookups.increment();
            TermStatistics stats = statsReader.getStatistics(normalized);
            if (stats != null) {
                return stats.totalFrequency();
            }
            statsMisses.increment();
        }

        // Fall back to index lookup
        indexLookups.increment();
        Term term = new Term("lemma", normalized);
        return reader.totalTermFreq(term);
    }

    public Map<String, Object> getStatsReport() {
        Map<String, Object> report = new HashMap<>();
        report.put("source", statsSource);
        report.put("path", statsPath);
        report.put("loaded", statsReader != null);
        report.put("stats_lookups", statsLookups.sum());
        report.put("stats_misses", statsMisses.sum());
        report.put("index_fallbacks", indexLookups.sum());
        if (statsReader != null) {
            report.put("total_tokens", statsReader.getTotalTokens());
            report.put("total_sentences", statsReader.getTotalSentences());
            report.put("unique_lemmas", statsReader.getUniqueLemmaCount());
        }
        return report;
    }

    public StatisticsReader getStatsReader() {
        return statsReader;
    }

    public Map<String, Object> getLastQueryReport() {
        QueryReport report = lastQueryReport.get();
        return report != null ? report.toMap() : Collections.emptyMap();
    }

    public static class QueryReport {
        private final String headword;
        private final long headwordFrequency;
        private final int totalSentences;
        private final int sampleSize;
        private final double scaleFactor;
        private final long collocateCount;
        private final int uniqueCollocates;
        private final long countMs;
        private final long searchMs;
        private final long tokenDecodeMs;
        private final long matchMs;
        private final long exampleMs;
        private final long scoreMs;
        private final long totalMs;

        public QueryReport(String headword, long headwordFrequency, int totalSentences, int sampleSize,
                           double scaleFactor, long collocateCount, int uniqueCollocates,
                           long countMs, long searchMs, long tokenDecodeMs, long matchMs,
                           long exampleMs, long scoreMs, long totalMs) {
            this.headword = headword;
            this.headwordFrequency = headwordFrequency;
            this.totalSentences = totalSentences;
            this.sampleSize = sampleSize;
            this.scaleFactor = scaleFactor;
            this.collocateCount = collocateCount;
            this.uniqueCollocates = uniqueCollocates;
            this.countMs = countMs;
            this.searchMs = searchMs;
            this.tokenDecodeMs = tokenDecodeMs;
            this.matchMs = matchMs;
            this.exampleMs = exampleMs;
            this.scoreMs = scoreMs;
            this.totalMs = totalMs;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("headword", headword);
            map.put("headword_frequency", headwordFrequency);
            map.put("total_sentences", totalSentences);
            map.put("sample_size", sampleSize);
            map.put("scale_factor", Math.round(scaleFactor * 100.0) / 100.0);
            map.put("collocate_count", collocateCount);
            map.put("unique_collocates", uniqueCollocates);
            map.put("timing_ms", Map.of(
                "count", countMs,
                "search", searchMs,
                "token_decode", tokenDecodeMs,
                "match", matchMs,
                "examples", exampleMs,
                "scoring", scoreMs,
                "total", totalMs
            ));
            return map;
        }
    }

    /**
     * Calculate logDice association score.
     */
    private double calculateLogDice(long cooccurrence, long freq1, long freq2) {
        if (cooccurrence <= 0 || freq1 <= 0 || freq2 <= 0) {
            return 0.0;
        }
        double dice = (2.0 * cooccurrence) / (freq1 + freq2);
        double logDice = Math.log(dice) / Math.log(2) + 14;
        return Math.max(0, Math.min(14, logDice));
    }

    /**
     * Extract max distance from CQL pattern.
     */
    private int extractMaxDistance(CQLPattern pattern) {
        for (CQLPattern.PatternElement elem : pattern.getElements()) {
            if (elem.getMaxDistance() > 0) {
                return elem.getMaxDistance();
            }
        }
        return 0;
    }

    /**
     * Extract constraints from CQL pattern elements.
     */
    private List<CQLPattern.Constraint> extractConstraints(CQLPattern pattern) {
        List<CQLPattern.Constraint> constraints = new ArrayList<>();
        for (CQLPattern.PatternElement elem : pattern.getElements()) {
            if (elem.getConstraint() != null) {
                constraints.add(elem.getConstraint());
            }
        }
        return constraints;
    }

    /**
     * Check if a token matches all constraints.
     */
    private boolean matchesConstraints(String lemma, String tag, List<CQLPattern.Constraint> constraints) {
        for (CQLPattern.Constraint constraint : constraints) {
            if (!matchesConstraint(lemma, tag, constraint)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if a token matches a single constraint.
     */
    private boolean matchesConstraint(String lemma, String tag, CQLPattern.Constraint constraint) {
        String field = constraint.getField();
        String pattern = constraint.getPattern();

        // Handle OR constraints
        if (constraint.isOr()) {
            if (constraint.getOrConstraints().isEmpty()) {
                String[] parts = pattern.split("\\|");
                for (String part : parts) {
                    if (matchesField(tag != null ? tag.toLowerCase() : "", field, part.trim())) {
                        return !constraint.isNegated();
                    }
                }
                return constraint.isNegated();
            } else {
                for (CQLPattern.Constraint orConstraint : constraint.getOrConstraints()) {
                    if (matchesConstraint(lemma, tag, orConstraint)) {
                        return !constraint.isNegated();
                    }
                }
                return constraint.isNegated();
            }
        }

        // Handle AND constraints
        if (constraint.isAnd() && !constraint.getAndConstraints().isEmpty()) {
            for (CQLPattern.Constraint andConstraint : constraint.getAndConstraints()) {
                if (!matchesConstraint(lemma, tag, andConstraint)) {
                    return constraint.isNegated();
                }
            }
            return !constraint.isNegated();
        }

        // Simple constraint
        String value = getFieldValue(lemma, tag, field);
        boolean matches = matchesField(value, field, pattern);
        return constraint.isNegated() ? !matches : matches;
    }

    private String getFieldValue(String lemma, String tag, String field) {
        return switch (field.toLowerCase()) {
            case "lemma" -> lemma != null ? lemma.toLowerCase() : "";
            case "word" -> lemma != null ? lemma.toLowerCase() : "";  // PRECOMPUTED: use lemma as proxy for word
            case "tag", "pos" -> tag != null ? tag.toLowerCase() : "";
            case "pos_group" -> tag != null ? getPosGroup(tag) : "";
            default -> "";
        };
    }

    private String getPosGroup(String tag) {
        if (tag == null || tag.isEmpty()) return "";
        String upper = tag.toUpperCase();
        if (upper.startsWith("NN") || upper.equals("NOUN")) return "noun";
        if (upper.startsWith("VB") || upper.equals("VERB")) return "verb";
        if (upper.startsWith("JJ") || upper.equals("ADJ")) return "adj";
        if (upper.startsWith("RB") || upper.equals("ADV")) return "adv";
        if (upper.startsWith("IN") || upper.equals("ADP")) return "prep";
        if (upper.startsWith("DT") || upper.equals("DET")) return "det";
        if (upper.startsWith("PR") || upper.equals("PRON")) return "pron";
        if (upper.startsWith("CC") || upper.equals("CCONJ") || upper.equals("SCONJ")) return "conj";
        return "";
    }

    /**
     * Precomputed algorithm: O(1) hash lookup in collocations.bin.
     * 
     * Falls back to SAMPLE_SCAN if:
     * - collocations.bin not loaded
     * - headword not in precomputed index
     * - CQL pattern requires filtering (not just lemma/pos constraints)
     */
    private List<WordSketchQueryExecutor.WordSketchResult> findCollocationsPrecomputed(
            String headword, String cqlPattern, double minLogDice, int maxResults) throws IOException {
        
        long startTime = System.currentTimeMillis();
        
        // Check if precomputed data available
        if (collocationsReader == null) {
            logger.warn("PRECOMPUTED algorithm selected but collocations.bin not loaded. Falling back to SAMPLE_SCAN.");
            return findCollocationsSampleScan(headword, cqlPattern, minLogDice, maxResults);
        }
        
        // Lookup precomputed collocations
        CollocationEntry entry = collocationsReader.getCollocations(headword);
        if (entry == null) {
            logger.debug("Headword '{}' not in precomputed index", headword);
            return Collections.emptyList();
        }
        
        // Parse CQL pattern for filtering
        CQLPattern collocatePattern = new CQLParser().parse(cqlPattern);
        List<CQLPattern.Constraint> collocateConstraints = extractConstraints(collocatePattern);
        
        // Build results
        List<WordSketchQueryExecutor.WordSketchResult> results = new ArrayList<>();
        
        for (Collocation coll : entry.collocates()) {
            // Apply logDice filter
            if (coll.logDice() < minLogDice) {
                continue;
            }
            
            // Apply CQL constraints
            if (!matchesConstraints(coll.lemma(), coll.pos(), collocateConstraints)) {
                continue;
            }

            // Quick validation: ensure the collocate actually appears in the *current* index
            // (precomputed data can be out-of-sync with the served index). First try statsReader
            // (fast); fall back to index docFreq. If the collocate never appears, skip it.
            long collocateFreq = 0L;
            if (statsReader != null) {
                collocateFreq = statsReader.getFrequency(coll.lemma());
            } else {
                collocateFreq = reader.docFreq(new Term("lemma", coll.lemma()));
            }

            if (collocateFreq == 0L) {
                logger.debug("Skipping stale precomputed collocate '{}' for headword '{}' (not found in index)", coll.lemma(), headword);
                continue;
            }

            // Optional stricter check: ensure at least one sentence contains both lemmas within window
            try {
                SpanTermQuery s1 = new SpanTermQuery(new Term("lemma", headword));
                SpanTermQuery s2 = new SpanTermQuery(new Term("lemma", coll.lemma()));
                SpanNearQuery near = SpanNearQuery.newUnorderedNearQuery("lemma")
                    .addClause(s1)
                    .addClause(s2)
                    .setSlop(collocationsReader != null ? collocationsReader.getWindowSize() : 5)
                    .build();
                TopDocs td = searcher.search(near, 1);
                if (td.totalHits.value() == 0) {
                    logger.debug("Skipping stale precomputed collocate '{}' for headword '{}' (no span match)", coll.lemma(), headword);
                    continue;
                }
            } catch (Exception e) {
                // If validation throws for any reason, don't block returning the collocate — just log
                logger.debug("Collocate validation error for '{}'+'{}': {}", headword, coll.lemma(), e.getMessage());
            }

            results.add(new WordSketchQueryExecutor.WordSketchResult(
                coll.lemma(),
                coll.pos(),
                coll.cooccurrence(),  // Use cooccurrence as the frequency for display
                coll.logDice(),       // logDice score
                0.0,                  // relativeFrequency (not used in precomputed)
                Collections.emptyList()  // No examples in precomputed mode
            ));

            if (results.size() >= maxResults) {
                break;
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        logger.info("PRECOMPUTED '{}': {} results in {}ms", headword, results.size(), duration);
        
        // Create report
        QueryReport report = new QueryReport(
            headword,
            entry.headwordFrequency(),
            0,  // totalSentences - not applicable
            0,  // sampleSize - not applicable
            1.0,  // scaleFactor
            entry.collocates().size(),  // collocateCount
            results.size(),  // uniqueCollocates
            0,  // countMs
            0,  // searchMs
            0,  // tokenDecodeMs
            0,  // matchMs
            0,  // exampleMs
            0,  // scoreMs
            duration  // totalMs
        );
        lastQueryReport.set(report);
        
        return results;
    }

    /**
     * Match a token field against a pattern.
     * Patterns support regex syntax: .* means "any characters", . means "any single character".
     * This matches the CQL convention where [tag="JJ.*"] matches JJ, JJS, JJR, etc.
     */
    private boolean matchesField(String value, String field, String pattern) {
        if (value == null) value = "";
        pattern = pattern.replace("\"", "").toLowerCase().trim();
        value = value.toLowerCase();

        // Use pattern as regex directly (case-insensitive)
        // Common patterns like "jj.*" or "nn.*" work as expected
        try {
            return value.matches(pattern);
        } catch (java.util.regex.PatternSyntaxException e) {
            // If pattern isn't valid regex, try exact match
            return value.equals(pattern);
        }
    }

    /**
     * Set a custom collocations file path (allows overriding default lookup location)
     */
    public void setCollocationPath(String collocationPath) throws IOException {
        if (java.nio.file.Files.exists(java.nio.file.Paths.get(collocationPath))) {
            CollocationsReader newReader = new CollocationsReader(collocationPath);
            // Close old reader if exists
            if (this.collocationsReader != null) {
                this.collocationsReader.close();
            }
            this.collocationsReader = newReader;
            logger.info("Loaded precomputed collocations from: {} ({} entries)", 
                collocationPath, newReader.getEntryCount());
        } else {
            logger.warn("Collocations file not found: {}", collocationPath);
        }
    }

    /**
     * Run a collocations integrity scan and return top-N headwords with most suspicious collocates.
     * A collocate is considered suspicious if either:
     *  - it does not occur in the current index/statistics; or
     *  - there is no sentence with both lemmas within the configured window.
     *
     * This is intentionally best-effort and uses the same validation performed at query-time.
     */
    public List<Map<String, Object>> collocationsIntegrityTopN(int topN) throws IOException {
        if (collocationsReader == null) {
            return Collections.emptyList();
        }

        Map<String, Integer> mismatchCounts = new HashMap<>();
        Map<String, List<Map<String, Object>>> details = new HashMap<>();

        for (String head : collocationsReader.getAllHeadwords()) {
            CollocationEntry entry = collocationsReader.getCollocations(head);
            if (entry == null) continue;

            String normalizedHead = head.toLowerCase(Locale.ROOT);
            long headIndexDf = getLemmaFrequencyForIntegrity(normalizedHead);
            long headStatsFreq = statsReader != null ? statsReader.getFrequency(normalizedHead) : -1L;
            boolean missingHeadword = headIndexDf == 0L;

            int mismatches = 0;
            List<Map<String, Object>> bads = new ArrayList<>();

            for (Collocation coll : entry.collocates()) {
                String normalizedColl = coll.lemma().toLowerCase(Locale.ROOT);
                long collIndexDf = getLemmaFrequencyForIntegrity(normalizedColl);
                long collStatsFreq = statsReader != null ? statsReader.getFrequency(normalizedColl) : -1L;
                boolean missingCollocate = collIndexDf == 0L;
                boolean noSpanMatch = false;

                // span check only when both lemmas are present
                if (!missingHeadword && !missingCollocate) {
                    try {
                        SpanTermQuery s1 = new SpanTermQuery(new Term("lemma", normalizedHead));
                        SpanTermQuery s2 = new SpanTermQuery(new Term("lemma", normalizedColl));
                        SpanNearQuery near = SpanNearQuery.newUnorderedNearQuery("lemma")
                                .addClause(s1)
                                .addClause(s2)
                                .setSlop(collocationsReader != null ? collocationsReader.getWindowSize() : 5)
                                .build();
                        TopDocs td = searcher.search(near, 1);
                        if (td.totalHits.value() == 0) {
                            noSpanMatch = true;
                        }
                    } catch (Exception e) {
                        // ignore and treat as noSpanMatch = false (best-effort)
                    }
                }

                if (missingHeadword || missingCollocate || noSpanMatch) {
                    mismatches++;
                    Map<String, Object> problem = new HashMap<>();
                    problem.put("lemma", coll.lemma());
                    problem.put("pos", coll.pos());
                    problem.put("logDice", coll.logDice());
                    problem.put("head_index_df", headIndexDf);
                    problem.put("collocate_index_df", collIndexDf);
                    if (headStatsFreq >= 0) problem.put("head_stats_freq", headStatsFreq);
                    if (collStatsFreq >= 0) problem.put("collocate_stats_freq", collStatsFreq);
                    if (missingHeadword && missingCollocate) {
                        problem.put("reason", "missing_both_lemmas");
                    } else if (missingHeadword) {
                        problem.put("reason", "missing_headword");
                    } else if (missingCollocate) {
                        problem.put("reason", "missing_collocate");
                    } else {
                        problem.put("reason", "both_present_but_no_span");
                    }
                    bads.add(problem);
                }
            }

            if (mismatches > 0) {
                mismatchCounts.put(head, mismatches);
                details.put(head, bads);
            }
        }

        // Convert to list sorted by mismatch count desc
        List<Map<String, Object>> report = new ArrayList<>();
        for (var e : mismatchCounts.entrySet()) {
            Map<String, Object> item = new HashMap<>();
            item.put("headword", e.getKey());
            CollocationEntry entry = collocationsReader.getCollocations(e.getKey());
            item.put("collocate_count", entry != null ? entry.collocates().size() : 0);
            item.put("mismatch_count", e.getValue());
            item.put("problems", details.getOrDefault(e.getKey(), Collections.emptyList()));
            report.add(item);
        }

        report.sort((a, b) -> Integer.compare((int)b.get("mismatch_count"), (int)a.get("mismatch_count")));
        if (report.size() > topN) return report.subList(0, topN);
        return report;
    }

    /**
     * Run collocations integrity check only for a provided headword list (limit problems per head).
     */
    public List<Map<String, Object>> collocationsIntegrityReportFor(List<String> heads, int perHeadLimit) throws IOException {
        if (collocationsReader == null) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> report = new ArrayList<>();
        for (String head : heads) {
            CollocationEntry entry = collocationsReader.getCollocations(head);
            if (entry == null) continue;

            String normalizedHead = head.toLowerCase(Locale.ROOT);
            long headIndexDf = getLemmaFrequencyForIntegrity(normalizedHead);
            long headStatsFreq = statsReader != null ? statsReader.getFrequency(normalizedHead) : -1L;
            boolean missingHeadword = headIndexDf == 0L;

            List<Map<String, Object>> problems = new ArrayList<>();
            for (Collocation coll : entry.collocates()) {
                String normalizedColl = coll.lemma().toLowerCase(Locale.ROOT);
                long collIndexDf = getLemmaFrequencyForIntegrity(normalizedColl);
                long collStatsFreq = statsReader != null ? statsReader.getFrequency(normalizedColl) : -1L;
                boolean missingCollocate = collIndexDf == 0L;
                boolean noSpanMatch = false;

                if (!missingHeadword && !missingCollocate) {
                    try {
                        SpanTermQuery s1 = new SpanTermQuery(new Term("lemma", normalizedHead));
                        SpanTermQuery s2 = new SpanTermQuery(new Term("lemma", normalizedColl));
                        SpanNearQuery near = SpanNearQuery.newUnorderedNearQuery("lemma")
                                .addClause(s1)
                                .addClause(s2)
                                .setSlop(collocationsReader != null ? collocationsReader.getWindowSize() : 5)
                                .build();
                        TopDocs td = searcher.search(near, 1);
                        if (td.totalHits.value() == 0) noSpanMatch = true;
                    } catch (Exception e) {
                        // best-effort
                    }
                }

                if (missingHeadword || missingCollocate || noSpanMatch) {
                    Map<String, Object> problem = new HashMap<>();
                    problem.put("lemma", coll.lemma());
                    problem.put("pos", coll.pos());
                    problem.put("logDice", coll.logDice());
                    problem.put("head_index_df", headIndexDf);
                    problem.put("collocate_index_df", collIndexDf);
                    if (headStatsFreq >= 0) problem.put("head_stats_freq", headStatsFreq);
                    if (collStatsFreq >= 0) problem.put("collocate_stats_freq", collStatsFreq);
                    if (missingHeadword && missingCollocate) {
                        problem.put("reason", "missing_both_lemmas");
                    } else if (missingHeadword) {
                        problem.put("reason", "missing_headword");
                    } else if (missingCollocate) {
                        problem.put("reason", "missing_collocate");
                    } else {
                        problem.put("reason", "both_present_but_no_span");
                    }
                    problems.add(problem);
                    if (problems.size() >= perHeadLimit) break;
                }
            }

            if (!problems.isEmpty()) {
                Map<String, Object> item = new HashMap<>();
                item.put("headword", head);
                item.put("collocate_count", entry.collocates().size());
                item.put("mismatch_count", problems.size());
                item.put("problems", problems);
                report.add(item);
            }
        }

        return report;
    }

    private long getLemmaFrequencyForIntegrity(String normalizedLemma) throws IOException {
        return reader.docFreq(new Term("lemma", normalizedLemma));
    }

    @Override
    public void close() throws IOException {
        reader.close();
        if (collocationsReader != null) {
            collocationsReader.close();
        }
        logger.info("HybridQueryExecutor closed");
    }
}

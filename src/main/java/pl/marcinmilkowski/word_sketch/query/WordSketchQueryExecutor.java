package pl.marcinmilkowski.word_sketch.query;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanMultiTermQueryWrapper;
import org.apache.lucene.queries.spans.SpanOrQuery;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanTermQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.store.FSDirectory;
import pl.marcinmilkowski.word_sketch.grammar.CQLParser;
import pl.marcinmilkowski.word_sketch.grammar.CQLPattern;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

/**
 * Query executor that finds collocations and computes association scores.
 * Supports full CQL grammar including labeled positions, OR/AND constraints,
 * agreement rules, lemma substitution, and repetition.
 */
public class WordSketchQueryExecutor {

    private final IndexSearcher searcher;
    private final CQLToLuceneCompiler compiler;
    private final IndexReader reader;

    public WordSketchQueryExecutor(String indexPath) throws IOException {
        this.reader = DirectoryReader.open(FSDirectory.open(Paths.get(indexPath)));
        this.searcher = new IndexSearcher(reader);
        this.compiler = new CQLToLuceneCompiler();
    }

    /**
     * Find collocations for a headword using a CQL pattern.
     * Uses CQLToLuceneCompiler for full pattern support.
     *
     * Supports:
     * - Labeled positions: 1:NOUN, 2:"N.*"
     * - OR constraints: [tag="JJ"|tag="RB"]
     * - AND constraints: [tag="PP" & word!="I"]
     * - Agreement rules: & 1.tag = 2.tag
     * - Lemma substitution: %(3.lemma)
     * - Repetition: {0,3}
     */
    public List<WordSketchResult> findCollocations(String headword, String cqlPattern,
                                                    double minLogDice, int maxResults)
            throws IOException {

        if (headword == null || headword.isEmpty()) {
            return Collections.emptyList();
        }

        // Get headword frequency
        long headwordFreq = getTotalFrequency(headword);
        if (headwordFreq == 0) {
            System.out.println(String.format("  Headword '%s' not found in corpus", headword));
            return Collections.emptyList();
        }

        // Parse the collocate pattern
        CQLPattern collocatePattern = new CQLParser().parse(cqlPattern);

        // Build constraint patterns from CQL pattern
        List<CQLPattern.Constraint> collocateConstraints = extractConstraints(collocatePattern);

        // Find all headword positions
        SpanTermQuery headwordQuery = new SpanTermQuery(new Term("lemma", headword.toLowerCase()));
        TopDocs headwordDocs = searcher.search(headwordQuery, Math.toIntExact(Math.min(headwordFreq, 100000)));

        System.out.println(String.format("    '%s': found %,d occurrences, scanning...", 
            headword, headwordDocs.scoreDocs.length));

        // Aggregate collocate frequencies
        Map<String, Long> lemmaFreqs = new HashMap<>();
        Map<String, String> lemmaPos = new HashMap<>();
        Map<String, List<String>> examples = new HashMap<>();

        // Calculate max distance from pattern
        int maxDist = extractMaxDistance(collocatePattern);
        if (maxDist <= 0) maxDist = 3; // Default

        long collocateCount = 0;
        int scanned = 0;
        long startTime = System.currentTimeMillis();
        int totalOccurrences = headwordDocs.scoreDocs.length;

        for (ScoreDoc hit : headwordDocs.scoreDocs) {
            Document doc = searcher.storedFields().document(hit.doc);
            int headwordDocId = doc.getField("doc_id").numericValue().intValue();
            int headwordPos = doc.getField("position").numericValue().intValue();

            // Find all documents in the same sentence (by doc_id)
            TermQuery sentenceQuery = new TermQuery(new Term("doc_id", String.valueOf(headwordDocId)));
            TopDocs sentenceDocs = searcher.search(sentenceQuery, 100);

            // Check each document in the sentence for position match
            for (ScoreDoc sentHit : sentenceDocs.scoreDocs) {
                Document sentDoc = searcher.storedFields().document(sentHit.doc);
                int sentPos = Integer.parseInt(sentDoc.get("position"));
                String nearbyLemma = sentDoc.get("lemma");
                String nearbyTag = sentDoc.get("tag");
                String sentence = sentDoc.get("sentence");

                // Skip headword itself
                if (sentPos == headwordPos) continue;

                // Check position distance
                if (Math.abs(sentPos - headwordPos) > maxDist) continue;

                if (nearbyLemma == null) continue;
                if (nearbyLemma.equalsIgnoreCase(headword)) continue;

                // Check CQL constraints
                if (!matchesConstraints(nearbyLemma, nearbyTag, collocateConstraints)) {
                    continue;
                }

                collocateCount++;
                String key = nearbyLemma.toLowerCase();

                lemmaFreqs.merge(key, 1L, Long::sum);
                lemmaPos.putIfAbsent(key, nearbyTag != null ? nearbyTag.toUpperCase() : "");

                if (!examples.containsKey(key)) {
                    examples.put(key, new ArrayList<>());
                }
                if (examples.get(key).size() < 3 && sentence != null) {
                    examples.get(key).add(sentence.trim());
                }
            }

            scanned++;

            // Progress indicator every 1000 occurrences
            if (scanned % 1000 == 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                double rate = scanned / (elapsed / 1000.0);
                int remaining = Math.min(totalOccurrences, 50000) - scanned;
                int eta = (int) (remaining / rate);
                System.out.print(String.format("\r      Scanned %,d/%,d occurrences, found %,d matches (%.0f occ/s, ETA %ds)    ",
                    scanned, Math.min(totalOccurrences, 50000), lemmaFreqs.size(), rate, eta));
            }

            // Limit scanned headwords for performance
            if (scanned >= 50000) break;
        }

        // Clear progress line
        if (scanned > 0) {
            System.out.println(String.format("\r      Scanned %,d occurrences in %.1fs, found %,d unique collocates",
                scanned, (System.currentTimeMillis() - startTime) / 1000.0, lemmaFreqs.size()));
        }

        // Calculate logDice for each collocate
        long totalMatches = Math.max(1, collocateCount);
        List<WordSketchResult> results = new ArrayList<>();

        for (Map.Entry<String, Long> entry : lemmaFreqs.entrySet()) {
            String lemma = entry.getKey();
            long freq = entry.getValue();

            long collocateTotalFreq = getTotalFrequency(lemma);
            if (collocateTotalFreq == 0) collocateTotalFreq = 1;

            double logDice = calculateLogDice(freq, headwordFreq, collocateTotalFreq);

            if (logDice >= minLogDice || minLogDice == 0) {
                double relFreq = (double) freq / totalMatches;
                WordSketchResult result = new WordSketchResult(
                    lemma,
                    lemmaPos.getOrDefault(lemma, ""),
                    freq,
                    logDice,
                    relFreq,
                    examples.getOrDefault(lemma, Collections.emptyList())
                );
                results.add(result);
            }
        }

        results.sort((a, b) -> Double.compare(b.getLogDice(), a.getLogDice()));

        return results.subList(0, Math.min(maxResults, results.size()));
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
            // Check if any OR part matches
            if (constraint.getOrConstraints().isEmpty()) {
                // Single OR pattern like tag="JJ"|tag="RB"
                String[] parts = pattern.split("\\|");
                for (String part : parts) {
                    if (matchesField(tag != null ? tag.toLowerCase() : "", field, part.trim())) {
                        return !constraint.isNegated();
                    }
                }
                return constraint.isNegated();
            } else {
                // Multiple OR constraints
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
            // All AND parts must match
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

    /**
     * Get field value from token.
     */
    private String getFieldValue(String lemma, String tag, String field) {
        switch (field.toLowerCase()) {
            case "lemma":
                return lemma != null ? lemma.toLowerCase() : "";
            case "tag":
                return tag != null ? tag.toLowerCase() : "";
            case "word":
                return lemma != null ? lemma.toLowerCase() : ""; // word field not stored separately
            default:
                return lemma != null ? lemma.toLowerCase() : "";
        }
    }

    /**
     * Check if a field value matches a pattern.
     * Handles wildcard patterns (glob): * matches any characters, ? matches single char.
     * In glob patterns, . is just a literal character (like in tag patterns "JJ.*").
     * Conversion: * -> .*, ? -> ., other chars literal.
     */
    private boolean matchesField(String value, String field, String pattern) {
        if (pattern.startsWith("\"")) {
            pattern = pattern.substring(1, pattern.length() - 1);
        }

        // Convert glob pattern to regex
        // * -> .*  (match any characters)
        // ? -> .   (match single character)
        // All other characters (including .) are treated literally
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append(".");
            } else if ("\\^$|()[]{}+".indexOf(c) >= 0) {
                // Escape regex special characters
                regex.append("\\").append(c);
            } else {
                // All other characters (including .) are treated literally
                // This means "JJ.*" matches tags starting with "JJ"
                regex.append(c);
            }
        }

        String fullRegex = "(?i)^" + regex + "$";
        return value.matches(fullRegex);
    }

    private long getTotalFrequency(String lemma) throws IOException {
        return reader.docFreq(new Term("lemma", lemma));
    }

    /**
     * Calculate logDice association score.
     * logDice = log2(2 * f(AB) / (f(A) + f(B))) + 14
     * Score ranges from 0 to 14, where 14 = perfect association.
     */
    private double calculateLogDice(long freqAB, long freqA, long freqB) {
        if (freqA == 0 || freqB == 0) {
            return 0.0;
        }
        double numerator = 2.0 * freqAB;
        double denominator = freqA + freqB;
        if (denominator == 0) {
            return 0.0;
        }
        double logDice = Math.log(numerator / denominator) / Math.log(2) + 14;
        return Math.max(0, Math.min(14, logDice)); // Clamp to [0, 14]
    }

    /**
     * Execute a custom CQL query and return matching sentences.
     */
    public List<ConcordanceResult> executeQuery(String cqlPattern, int maxResults)
            throws IOException {

        CQLPattern pattern = new CQLParser().parse(cqlPattern);
        SpanQuery query = compiler.compile(pattern);

        TopDocs topDocs = searcher.search(query, maxResults);
        ScoreDoc[] hits = topDocs.scoreDocs;

        List<ConcordanceResult> results = new ArrayList<>();

        for (ScoreDoc hit : hits) {
            Document doc = searcher.storedFields().document(hit.doc);
            results.add(new ConcordanceResult(
                doc.get("sentence"),
                doc.get("lemma"),
                doc.get("tag"),
                doc.get("word"),
                Integer.parseInt(doc.get("start_offset")),
                Integer.parseInt(doc.get("end_offset"))
            ));
        }

        return results;
    }

    /**
     * Verify a CQL pattern against a token window with support for labeled positions.
     * This method uses the CQLVerifier to check exact pattern matching.
     */
    public boolean verifyPattern(CQLPattern pattern, TokenWindow window, int headwordPosition) {
        CQLVerifier verifier = new CQLVerifier();
        CQLVerifier.VerificationResult result = verifier.verifyForCollocate(pattern, window, headwordPosition);
        return result.isMatched();
    }

    /**
     * Load tokens for a sentence window around the given headword position.
     */
    public TokenWindow loadTokenWindow(int headwordDocId, int headwordPosition, String sentenceId, int windowSize)
            throws IOException {
        List<Token> tokens = new ArrayList<>();
        int startDocId = Math.max(0, headwordDocId - windowSize);
        int endDocId = Math.min(reader.maxDoc() - 1, headwordDocId + windowSize);

        // Collect documents for the window
        List<Document> docs = new ArrayList<>();
        for (int docId = startDocId; docId <= endDocId; docId++) {
            try {
                Document doc = searcher.storedFields().document(docId);
                String docSentence = doc.get("sentence");

                // Only include documents from the same sentence
                if (sentenceId != null && docSentence != null && docSentence.equals(doc.get("sentence"))) {
                    docs.add(doc);
                }
            } catch (Exception e) {
                // Skip invalid documents
            }
        }

        if (docs.isEmpty()) {
            return new TokenWindow(tokens, startDocId, endDocId, 0);
        }

        // Build tokens from documents
        for (Document doc : docs) {
            String lemma = doc.get("lemma");
            String word = doc.get("word");
            String tag = doc.get("tag");
            String posGroup = doc.get("pos_group");
            int position = Integer.parseInt(doc.get("position"));
            String sentence = doc.get("sentence");

            int sid = 0;
            try {
                sid = Integer.parseInt(doc.get("doc_id"));
            } catch (NumberFormatException e) {
                // Use 0 as fallback
            }

            tokens.add(new Token(lemma, word, tag, posGroup, position, sid, sentence));
        }

        return new TokenWindow(tokens, startDocId, endDocId,
            tokens.isEmpty() ? 0 : tokens.get(0).getSentenceId());
    }

    /**
     * Check if a pattern has labeled positions (requires verifier).
     */
    public boolean hasLabeledPositions(CQLPattern pattern) {
        for (CQLPattern.PatternElement element : pattern.getElements()) {
            if (element.getPosition() > 0) {
                return true;
            }
        }
        return false;
    }

    public static class WordSketchResult {
        private final String lemma;
        private final String pos;
        private final long frequency;
        private final double logDice;
        private final double relativeFrequency;
        private final List<String> examples;

        public WordSketchResult(String lemma, String pos, long frequency,
                               double logDice, double relativeFrequency,
                               List<String> examples) {
            this.lemma = lemma;
            this.pos = pos;
            this.frequency = frequency;
            this.logDice = logDice;
            this.relativeFrequency = relativeFrequency;
            this.examples = examples;
        }

        public String getLemma() { return lemma; }
        public String getPos() { return pos; }
        public long getFrequency() { return frequency; }
        public double getLogDice() { return logDice; }
        public double getRelativeFrequency() { return relativeFrequency; }
        public List<String> getExamples() { return examples; }
    }

    public static class ConcordanceResult {
        private final String sentence;
        private final String lemma;
        private final String tag;
        private final String word;
        private final int startOffset;
        private final int endOffset;

        public ConcordanceResult(String sentence, String lemma, String tag,
                                String word, int startOffset, int endOffset) {
            this.sentence = sentence;
            this.lemma = lemma;
            this.tag = tag;
            this.word = word;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        public String getSentence() { return sentence; }
        public String getLemma() { return lemma; }
        public String getTag() { return tag; }
        public String getWord() { return word; }
        public int getStartOffset() { return startOffset; }
        public int getEndOffset() { return endOffset; }
    }

    public void close() throws IOException {
        reader.close();
    }

    /**
     * Get frequency of a specific lemma in the index.
     */
    public long getLemmaFrequency(String lemma) throws IOException {
        return reader.docFreq(new Term("lemma", lemma));
    }

    /**
     * Find words by POS tag pattern (e.g., "JJ*" for adjectives, "NN*" for nouns).
     */
    public List<WordSketchResult> findByTagPattern(String tagPattern, int maxResults) throws IOException {
        org.apache.lucene.index.Term term = new org.apache.lucene.index.Term("tag", tagPattern.replace("*", ".*"));
        org.apache.lucene.search.WildcardQuery query = new org.apache.lucene.search.WildcardQuery(term);
        org.apache.lucene.queries.spans.SpanMultiTermQueryWrapper<?> spanQuery =
            new org.apache.lucene.queries.spans.SpanMultiTermQueryWrapper<>(query);

        TopDocs topDocs = searcher.search(spanQuery, maxResults * 10);
        ScoreDoc[] hits = topDocs.scoreDocs;

        Map<String, Long> lemmaFreqs = new HashMap<>();
        Map<String, String> examples = new HashMap<>();

        for (ScoreDoc hit : hits) {
            Document doc = searcher.storedFields().document(hit.doc);
            String lemma = doc.get("lemma");
            String sentence = doc.get("sentence");

            if (lemma != null) {
                lemmaFreqs.merge(lemma, 1L, Long::sum);
                if (!examples.containsKey(lemma)) {
                    examples.put(lemma, sentence);
                }
            }
        }

        List<WordSketchResult> results = new ArrayList<>();
        for (Map.Entry<String, Long> entry : lemmaFreqs.entrySet()) {
            results.add(new WordSketchResult(
                entry.getKey(), "", entry.getValue(),
                Math.log10(entry.getValue() + 1) * 2,
                (double) entry.getValue() / hits.length,
                Collections.singletonList(examples.get(entry.getKey()))
            ));
        }

        results.sort((a, b) -> Long.compare(b.getFrequency(), a.getFrequency()));
        return results.subList(0, Math.min(maxResults, results.size()));
    }

    public static void main(String[] args) throws IOException {
        String indexPath = args.length > 0 ? args[0] : "target/corpus-1m";

        System.out.println("Word Sketch Query Executor - 1M Token Corpus");
        System.out.println("==============================================");
        System.out.println(String.format("Index: %s", indexPath));

        try (WordSketchExecutor executor = new WordSketchExecutor(indexPath)) {
            // Debug: Check what tags exist in the index
            System.out.println("Checking index structure...");
            System.out.println("Sample lemma frequencies:");
            String[] testLemmas = {"time", "problem", "good", "people", "new"};
            for (String lemma : testLemmas) {
                long freq = executor.executor.getLemmaFrequency(lemma);
                System.out.println("  " + lemma + ": " + freq);
            }

            // Debug: Check what the CQL pattern parses to
            String testPattern = "[tag=\"JJ.*\"]";
            System.out.println("\nTesting CQL pattern: " + testPattern);
            try {
                var parsed = new CQLParser().parse(testPattern);
                System.out.println("Parsed elements: " + parsed.getElements().size());
                for (var elem : parsed.getElements()) {
                    System.out.println("  target='" + elem.getTarget() + "', position=" + elem.getPosition() +
                        ", hasConstraint=" + (elem.getConstraint() != null));
                    if (elem.getConstraint() != null) {
                        System.out.println("    constraint: field=" + elem.getConstraint().getField() +
                            ", pattern=" + elem.getConstraint().getPattern());
                    }
                }

                // Compile to see what query is produced
                var compiler = new CQLToLuceneCompiler();
                var query = compiler.compile(parsed);
                System.out.println("Query type: " + query.getClass().getSimpleName());
            } catch (Exception e) {
                System.out.println("Parse error: " + e.getMessage());
            }

            // Test a direct query
            System.out.println("\nTesting direct tag query [tag=\"JJ\"]...");
            try {
                var directResults = executor.executor.executeQuery("[tag=\"JJ\"]", 10);
                System.out.println("Direct query found " + directResults.size() + " results");
                for (var r : directResults) {
                    System.out.println("  " + r.getWord() + "/" + r.getTag() + ": " +
                        (r.getSentence() != null ? r.getSentence().substring(0, Math.min(80, r.getSentence().length())) : "null"));
                }
            } catch (Exception e) {
                System.out.println("Direct query error: " + e.getMessage());
            }

            // Debug: Check the actual Lucene query being built
            System.out.println("\nDebug: Testing wildcard query directly...");
            try {
                var pattern = new CQLParser().parse("[tag=\"JJ*\"]");
                System.out.println("Pattern elements: " + pattern.getElements().size());
                var compiler = new CQLToLuceneCompiler();
                var query = compiler.compile(pattern);
                System.out.println("Query: " + query);

                // Test search
                var searcher = new IndexSearcher(executor.executor.reader);
                var topDocs = searcher.search(query, 100);
                System.out.println("Search found " + topDocs.totalHits + " hits");
            } catch (Exception e) {
                System.out.println("Debug error: " + e.getMessage());
                e.printStackTrace();
            }

            System.out.println("\n=== Word Sketch: problem (adjectives) ===");
            System.out.println("Pattern: [tag=\"jj.*\"] ~ {0,3}");
            System.out.println();

            List<WordSketchResult> results = executor.findCollocations(
                "problem", "[tag=\"jj.*\"] ~ {0,3}", 0, 20);
            System.out.println("Results size: " + results.size());

            for (int i = 0; i < Math.min(10, results.size()); i++) {
                WordSketchResult r = results.get(i);
                System.out.println(String.format("  %-15s %,5d  logDice: %.2f  %s",
                    r.getLemma(), r.getFrequency(), r.getLogDice(),
                    r.getExamples().isEmpty() ? "" : r.getExamples().get(0).replace("\n", " ")));
            }
            System.out.println();

            // Test adjective collocates of "time"
            System.out.println("=== Word Sketch: time (adjectives) ===");
            System.out.println("Pattern: [tag=\"jj.*\"] ~ {0,3}");
            System.out.println();

            results = executor.findCollocations("time", "[tag=\"jj.*\"] ~ {0,3}", 0, 20);

            for (int i = 0; i < Math.min(10, results.size()); i++) {
                WordSketchResult r = results.get(i);
                System.out.println(String.format("  %-15s %,5d  logDice: %.2f  %s",
                    r.getLemma(), r.getFrequency(), r.getLogDice(),
                    r.getExamples().isEmpty() ? "" : r.getExamples().get(0).replace("\n", " ")));
            }
            System.out.println();

            // Test verbs with "people"
            System.out.println("=== Word Sketch: people (verbs) ===");
            System.out.println("Pattern: [tag=\"vb.*\"] ~ {0,3}");
            System.out.println();

            results = executor.findCollocations("people", "[tag=\"vb.*\"] ~ {0,3}", 0, 15);

            for (int i = 0; i < Math.min(10, results.size()); i++) {
                WordSketchResult r = results.get(i);
                System.out.println(String.format("  %-15s %,5d  logDice: %.2f  %s",
                    r.getLemma(), r.getFrequency(), r.getLogDice(),
                    r.getExamples().isEmpty() ? "" : r.getExamples().get(0).replace("\n", " ")));
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Simple wrapper for testing - uses WordSketchQueryExecutor internally.
     */
    public static class WordSketchExecutor implements AutoCloseable {
        private final WordSketchQueryExecutor executor;

        public WordSketchExecutor(String indexPath) throws IOException {
            this.executor = new WordSketchQueryExecutor(indexPath);
        }

        public List<WordSketchResult> findCollocations(String headword, String pattern,
                                                        double minLogDice, int maxResults) throws IOException {
            return executor.findCollocations(headword, pattern, minLogDice, maxResults);
        }

        @Override
        public void close() throws IOException {
            executor.close();
        }
    }

    /**
     * Optimized method for snowball: find adjectives linked to nouns via linking verbs.
     * Instead of scanning all noun occurrences, we search for linking verbs directly.
     * Then manually check if next token is adjective and if seed nouns appear nearby.
     */
    public Map<String, List<String>> findLinkingVerbPredicates(Set<String> seedNouns, 
                                                                double minLogDice, 
                                                                int maxResults) throws IOException {
        Map<String, List<String>> results = new HashMap<>();
        
        // Build query for linking verbs only (simpler than multi-field pattern)
        // Note: Search on lemma field, not word field (word contains inflected forms)
        String[] linkingVerbs = {"be", "remain", "seem", "appear", "feel", "get", "become", "look", "smell", "taste"};
        SpanQuery[] verbQueries = new SpanQuery[linkingVerbs.length];
        for (int i = 0; i < linkingVerbs.length; i++) {
            verbQueries[i] = new SpanTermQuery(new Term("lemma", linkingVerbs[i]));
        }
        SpanOrQuery verbQuery = new SpanOrQuery(verbQueries);
        
        System.out.println("    Searching for linking verbs...");
        long startTime = System.currentTimeMillis();
        
        // Search for all linking verb occurrences (limit to reasonable number)
        TopDocs verbMatches = searcher.search(verbQuery, 50000);
        
        System.out.println(String.format("    Found %,d linking verb occurrences in %.1fs", 
            verbMatches.scoreDocs.length, (System.currentTimeMillis() - startTime) / 1000.0));
        
        System.out.println(String.format("    Found %,d linking verb occurrences in %.1fs", 
            verbMatches.scoreDocs.length, (System.currentTimeMillis() - startTime) / 1000.0));
        
        // For each linking verb, check if next token is adjective and if seed nouns nearby
        Map<String, Map<String, Long>> nounAdjectiveCounts = new HashMap<>();
        int validPatterns = 0;
        
        for (ScoreDoc hit : verbMatches.scoreDocs) {
            Document doc = searcher.storedFields().document(hit.doc);
            int verbDocId = doc.getField("doc_id").numericValue().intValue();
            int verbPos = doc.getField("position").numericValue().intValue();
            
            // Find all tokens in the same sentence
            TermQuery sentenceQuery = new TermQuery(new Term("doc_id", String.valueOf(verbDocId)));
            TopDocs sentenceDocs = searcher.search(sentenceQuery, 100);
            
            // Check each token: find adjective at verbPos+1, find seed nouns within window
            String adjective = null;
            for (ScoreDoc sentHit : sentenceDocs.scoreDocs) {
                Document sentDoc = searcher.storedFields().document(sentHit.doc);
                int sentPos = Integer.parseInt(sentDoc.get("position"));
                String lemma = sentDoc.get("lemma");
                String tag = sentDoc.get("tag");
                
                // Adjective is at linking verb position + 1
                if (sentPos == verbPos + 1 && tag != null && tag.startsWith("JJ")) {
                    adjective = lemma.toLowerCase();
                    validPatterns++;
                    break; // Found adjective, continue to check for nouns
                }
            }
            
            // If we found an adjective after the linking verb, check for nearby seed nouns
            if (adjective != null) {
                for (ScoreDoc sentHit : sentenceDocs.scoreDocs) {
                    Document sentDoc = searcher.storedFields().document(sentHit.doc);
                    int sentPos = Integer.parseInt(sentDoc.get("position"));
                    String lemma = sentDoc.get("lemma");
                    
                    // Check if seed noun appears within window
                    if (lemma != null && seedNouns.contains(lemma.toLowerCase()) && 
                        Math.abs(sentPos - verbPos) <= 3) {
                        
                        // Found: seed noun within window of linking-verb + adjective
                        nounAdjectiveCounts
                            .computeIfAbsent(lemma.toLowerCase(), k -> new HashMap<>())
                            .merge(adjective, 1L, Long::sum);
                    }
                }
            }
        }
        
        System.out.println(String.format("    Valid linking-verb + adjective patterns: %,d", validPatterns));
        
        // Convert counts to results
        for (Map.Entry<String, Map<String, Long>> entry : nounAdjectiveCounts.entrySet()) {
            String noun = entry.getKey();
            Map<String, Long> adjectiveCounts = entry.getValue();
            
            // Sort by frequency and take top results
            List<String> topAdjectives = adjectiveCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(maxResults)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
            
            results.put(noun, topAdjectives);
        }
        
        return results;
    }

    /**
     * Optimized method for finding nouns modified by adjectives (attributive use).
     * Instead of scanning all adjective occurrences, search for adjective tags first,
     * then check if any seed adjectives match and if nouns appear nearby.
     */
    public Map<String, List<String>> findAttributiveNouns(Set<String> seedAdjectives,
                                                           double minLogDice,
                                                           int maxResults) throws IOException {
        Map<String, List<String>> results = new HashMap<>();
        
        System.out.println("    Searching for seed adjectives by lemma...");
        long startTime = System.currentTimeMillis();
        
        // For each adjective, check if it's one of our seed adjectives and find nearby nouns
        Map<String, Map<String, Long>> adjectiveNounCounts = new HashMap<>();
        int matchingSeedAdjs = 0;
        
        // Batch adjectives to avoid "TooManyNestedClauses" error (max 1024 clauses)
        List<String> adjList = new ArrayList<>(seedAdjectives);
        int batchSize = 500;
        int totalHits = 0;
        
        for (int batchStart = 0; batchStart < adjList.size(); batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, adjList.size());
            List<String> batch = adjList.subList(batchStart, batchEnd);
            
            // Search for this batch of seed adjectives by lemma
            SpanQuery[] adjQueries = new SpanQuery[batch.size()];
            for (int i = 0; i < batch.size(); i++) {
                adjQueries[i] = new SpanTermQuery(new Term("lemma", batch.get(i)));
            }
            SpanOrQuery adjQuery = new SpanOrQuery(adjQueries);
            
            // Search for adjective occurrences (limit to reasonable number per batch)
            TopDocs batchMatches = searcher.search(adjQuery, 100000);
            totalHits += batchMatches.scoreDocs.length;
        
            for (ScoreDoc hit : batchMatches.scoreDocs) {
                Document doc = searcher.storedFields().document(hit.doc);
                int adjDocId = doc.getField("doc_id").numericValue().intValue();
                int adjPos = doc.getField("position").numericValue().intValue();
                String lemma = doc.get("lemma");
                
                // Check if this is one of our seed adjectives (should always be true)
                if (lemma == null || !seedAdjectives.contains(lemma.toLowerCase())) {
                    continue;
                }
                matchingSeedAdjs++;
                String adjective = lemma.toLowerCase();
                
                // Find all tokens in the same sentence
                TermQuery sentenceQuery = new TermQuery(new Term("doc_id", String.valueOf(adjDocId)));
                TopDocs sentenceDocs = searcher.search(sentenceQuery, 100);
                
                // Check for nearby nouns (within 3 tokens)
                for (ScoreDoc sentHit : sentenceDocs.scoreDocs) {
                    Document sentDoc = searcher.storedFields().document(sentHit.doc);
                    int sentPos = Integer.parseInt(sentDoc.get("position"));
                    String nounLemma = sentDoc.get("lemma");
                    String tag = sentDoc.get("tag");
                    
                    // Check if nearby token is a noun
                    if (nounLemma != null && tag != null && tag.startsWith("NN") &&
                        Math.abs(sentPos - adjPos) <= 3 && sentPos != adjPos) {
                        
                        adjectiveNounCounts
                            .computeIfAbsent(adjective, k -> new HashMap<>())
                            .merge(nounLemma.toLowerCase(), 1L, Long::sum);
                    }
                }
            }
        }
        
        System.out.println(String.format("    Found %,d adjective occurrences in %.1fs",
            totalHits, (System.currentTimeMillis() - startTime) / 1000.0));
        System.out.println(String.format("    Matched %,d seed adjective occurrences", matchingSeedAdjs));
        
        // Convert counts to results
        for (Map.Entry<String, Map<String, Long>> entry : adjectiveNounCounts.entrySet()) {
            String adjective = entry.getKey();
            Map<String, Long> nounCounts = entry.getValue();
            
            // Sort by frequency and take top results
            List<String> topNouns = nounCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(maxResults)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
            
            results.put(adjective, topNouns);
        }
        
        return results;
    }
}

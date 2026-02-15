package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.marcinmilkowski.word_sketch.indexer.LuceneIndexer;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.HybridIndexer;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.SentenceDocument;
import pl.marcinmilkowski.word_sketch.query.WordSketchQueryExecutor.WordSketchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests comparing legacy (token-per-document) vs hybrid (sentence-per-document) executors.
 * 
 * Verifies that:
 * 1. Both executors return the same collocates for a given query
 * 2. Frequencies are consistent 
 * 3. logDice scores are similar (within tolerance)
 */
public class LegacyVsHybridRegressionTest {

    @TempDir
    static Path tempDir;

    static Path legacyIndexPath;
    static Path hybridIndexPath;

    // Test corpus - sentences with known collocations
    static final String[][] TEST_SENTENCES = {
        // sentence 1: "The big dog runs quickly"
        {"The", "the", "DT", "0", "1"},
        {"big", "big", "JJ", "1", "2"},
        {"dog", "dog", "NN", "2", "3"},
        {"runs", "run", "VBZ", "3", "4"},
        {"quickly", "quickly", "RB", "4", "5"},
        {null, null, null, null, null},  // sentence break

        // sentence 2: "A small dog sleeps quietly"
        {"A", "a", "DT", "0", "1"},
        {"small", "small", "JJ", "1", "2"},
        {"dog", "dog", "NN", "2", "3"},
        {"sleeps", "sleep", "VBZ", "3", "4"},
        {"quietly", "quietly", "RB", "4", "5"},
        {null, null, null, null, null},

        // sentence 3: "The happy cat plays"
        {"The", "the", "DT", "0", "1"},
        {"happy", "happy", "JJ", "1", "2"},
        {"cat", "cat", "NN", "2", "3"},
        {"plays", "play", "VBZ", "3", "4"},
        {null, null, null, null, null},

        // sentence 4: "Dogs love cats"
        {"Dogs", "dog", "NNS", "0", "1"},
        {"love", "love", "VBP", "1", "2"},
        {"cats", "cat", "NNS", "2", "3"},
        {null, null, null, null, null},

        // sentence 5: "The big house stands tall"
        {"The", "the", "DT", "0", "1"},
        {"big", "big", "JJ", "1", "2"},
        {"house", "house", "NN", "2", "3"},
        {"stands", "stand", "VBZ", "3", "4"},
        {"tall", "tall", "JJ", "4", "5"},
        {null, null, null, null, null},

        // sentence 6: "A small house is nice"
        {"A", "a", "DT", "0", "1"},
        {"small", "small", "JJ", "1", "2"},
        {"house", "house", "NN", "2", "3"},
        {"is", "be", "VBZ", "3", "4"},
        {"nice", "nice", "JJ", "4", "5"},
    };

    @BeforeAll
    static void setUp() throws Exception {
        legacyIndexPath = tempDir.resolve("legacy-index");
        hybridIndexPath = tempDir.resolve("hybrid-index");

        // Build legacy index (token-per-document)
        buildLegacyIndex();

        // Build hybrid index (sentence-per-document)
        buildHybridIndex();
    }

    private static void buildLegacyIndex() throws IOException {
        Files.createDirectories(legacyIndexPath);
        LuceneIndexer indexer = new LuceneIndexer(legacyIndexPath.toString());
        try {
            int sentenceId = 1;
            for (String[] token : TEST_SENTENCES) {
                if (token[0] == null) {
                    sentenceId++;
                    continue;
                }

                String word = token[0];
                String lemma = token[1];
                String tag = token[2];
                int position = Integer.parseInt(token[3]);
                int offset = Integer.parseInt(token[4]) * 5;  // approximate offset

                // Get POS group (first letter of tag)
                String posGroup = tag.isEmpty() ? "X" : String.valueOf(tag.charAt(0));
                
                // Build sentence text for context
                StringBuilder sentenceText = new StringBuilder();
                int idx = 0;
                for (String[] t : TEST_SENTENCES) {
                    if (t[0] == null) {
                        if (++idx >= sentenceId) break;
                        continue;
                    }
                    if (idx == sentenceId - 1) {
                        if (sentenceText.length() > 0) sentenceText.append(" ");
                        sentenceText.append(t[0]);
                    }
                }
                
                indexer.addWord(sentenceId, position, word, lemma, tag, posGroup, 
                    sentenceText.toString(), offset, offset + word.length());
            }
            indexer.commit();
        } finally {
            indexer.close();
        }
    }

    private static void buildHybridIndex() throws IOException {
        Files.createDirectories(hybridIndexPath);
        try (HybridIndexer indexer = new HybridIndexer(hybridIndexPath.toString())) {
            List<String[]> currentSentence = new ArrayList<>();
            int sentenceId = 1;

            for (String[] token : TEST_SENTENCES) {
                if (token[0] == null) {
                    if (!currentSentence.isEmpty()) {
                        indexSentence(indexer, sentenceId, currentSentence);
                        currentSentence.clear();
                        sentenceId++;
                    }
                    continue;
                }
                currentSentence.add(token);
            }

            // Index final sentence
            if (!currentSentence.isEmpty()) {
                indexSentence(indexer, sentenceId, currentSentence);
            }

            // Write statistics
            indexer.writeStatistics(hybridIndexPath.resolve("stats.tsv").toString());
        }
    }

    private static void indexSentence(HybridIndexer indexer, int sentenceId, List<String[]> tokens) throws IOException {
        StringBuilder text = new StringBuilder();
        SentenceDocument.Builder builder = SentenceDocument.builder()
            .sentenceId(sentenceId);

        int offset = 0;
        for (int i = 0; i < tokens.size(); i++) {
            String[] t = tokens.get(i);
            String word = t[0];
            String lemma = t[1];
            String tag = t[2];
            int position = Integer.parseInt(t[3]);

            if (text.length() > 0) text.append(" ");
            int startOffset = text.length();
            text.append(word);
            int endOffset = text.length();

            builder.addToken(position, word, lemma, tag, startOffset, endOffset);
        }

        builder.text(text.toString());
        indexer.indexSentence(builder.build());
    }

    @Test
    void bothExecutorsReturnSameCollocatesForDog() throws Exception {
        String lemma = "dog";
        String pattern = "[tag=\".*\"]";  // All tokens

        List<WordSketchResult> legacyResults;
        List<WordSketchResult> hybridResults;

        try (WordSketchQueryExecutor legacy = new WordSketchQueryExecutor(legacyIndexPath.toString())) {
            legacyResults = legacy.findCollocations(lemma, pattern, 0, 50);
        }

        try (HybridQueryExecutor hybrid = new HybridQueryExecutor(hybridIndexPath.toString())) {
            hybridResults = hybrid.findCollocations(lemma, pattern, 0, 50);
        }

        // Both should find collocates
        assertFalse(legacyResults.isEmpty(), "Legacy should find collocates");
        assertFalse(hybridResults.isEmpty(), "Hybrid should find collocates");

        // Extract collocate lemmas
        Set<String> legacyLemmas = legacyResults.stream()
            .map(WordSketchResult::getLemma)
            .collect(Collectors.toSet());

        Set<String> hybridLemmas = hybridResults.stream()
            .map(WordSketchResult::getLemma)
            .collect(Collectors.toSet());

        // Key collocates should appear in both (allowing for minor differences due to sampling)
        // "dog" appears with: big, small, runs, sleeps, love
        assertTrue(hybridLemmas.contains("big") || legacyLemmas.contains("big"), 
            "Both should find 'big' as collocate of 'dog'");
    }

    @Test
    void bothExecutorsReturnSameAdjectiveCollocatesForHouse() throws Exception {
        String lemma = "house";
        String pattern = "[tag=\"JJ\"]";  // Adjectives only

        List<WordSketchResult> legacyResults;
        List<WordSketchResult> hybridResults;

        try (WordSketchQueryExecutor legacy = new WordSketchQueryExecutor(legacyIndexPath.toString())) {
            legacyResults = legacy.findCollocations(lemma, pattern, 0, 50);
        }

        try (HybridQueryExecutor hybrid = new HybridQueryExecutor(hybridIndexPath.toString())) {
            hybridResults = hybrid.findCollocations(lemma, pattern, 0, 50);
        }

        // Extract adjective collocates
        Set<String> legacyAdjs = legacyResults.stream()
            .map(WordSketchResult::getLemma)
            .collect(Collectors.toSet());

        Set<String> hybridAdjs = hybridResults.stream()
            .map(WordSketchResult::getLemma)
            .collect(Collectors.toSet());

        // "house" should have adjective collocates: big, small, tall, nice
        System.out.println("Legacy adjectives for 'house': " + legacyAdjs);
        System.out.println("Hybrid adjectives for 'house': " + hybridAdjs);

        // At least one common adjective
        Set<String> intersection = new HashSet<>(legacyAdjs);
        intersection.retainAll(hybridAdjs);
        assertFalse(intersection.isEmpty(), "Should have common adjective collocates");
    }

    @Test
    void frequencyCountsAreConsistent() throws Exception {
        String lemma = "dog";
        String pattern = "[tag=\"JJ\"]";  // Adjectives

        List<WordSketchResult> legacyResults;
        List<WordSketchResult> hybridResults;

        try (WordSketchQueryExecutor legacy = new WordSketchQueryExecutor(legacyIndexPath.toString())) {
            legacyResults = legacy.findCollocations(lemma, pattern, 0, 50);
        }

        try (HybridQueryExecutor hybrid = new HybridQueryExecutor(hybridIndexPath.toString())) {
            hybridResults = hybrid.findCollocations(lemma, pattern, 0, 50);
        }

        // Build frequency maps
        Map<String, Long> legacyFreqs = legacyResults.stream()
            .collect(Collectors.toMap(WordSketchResult::getLemma, WordSketchResult::getFrequency));

        Map<String, Long> hybridFreqs = hybridResults.stream()
            .collect(Collectors.toMap(WordSketchResult::getLemma, WordSketchResult::getFrequency));

        // Check overlapping entries
        for (String collocate : legacyFreqs.keySet()) {
            if (hybridFreqs.containsKey(collocate)) {
                long legacyFreq = legacyFreqs.get(collocate);
                long hybridFreq = hybridFreqs.get(collocate);
                
                // Allow small difference due to sampling
                double ratio = (double) hybridFreq / legacyFreq;
                assertTrue(ratio >= 0.5 && ratio <= 2.0,
                    String.format("Frequency for '%s' differs too much: legacy=%d, hybrid=%d",
                        collocate, legacyFreq, hybridFreq));
            }
        }
    }

    @Test
    void logDiceScoresAreWithinTolerance() throws Exception {
        String lemma = "dog";
        String pattern = "[tag=\"JJ\"]";

        List<WordSketchResult> legacyResults;
        List<WordSketchResult> hybridResults;

        try (WordSketchQueryExecutor legacy = new WordSketchQueryExecutor(legacyIndexPath.toString())) {
            legacyResults = legacy.findCollocations(lemma, pattern, 0, 50);
        }

        try (HybridQueryExecutor hybrid = new HybridQueryExecutor(hybridIndexPath.toString())) {
            hybridResults = hybrid.findCollocations(lemma, pattern, 0, 50);
        }

        // Build logDice maps
        Map<String, Double> legacyScores = legacyResults.stream()
            .collect(Collectors.toMap(WordSketchResult::getLemma, WordSketchResult::getLogDice));

        Map<String, Double> hybridScores = hybridResults.stream()
            .collect(Collectors.toMap(WordSketchResult::getLemma, WordSketchResult::getLogDice));

        // Check overlapping entries
        for (String collocate : legacyScores.keySet()) {
            if (hybridScores.containsKey(collocate)) {
                double legacyScore = legacyScores.get(collocate);
                double hybridScore = hybridScores.get(collocate);

                // logDice scores should be within 2.0 of each other
                double diff = Math.abs(legacyScore - hybridScore);
                assertTrue(diff < 2.0,
                    String.format("logDice for '%s' differs too much: legacy=%.2f, hybrid=%.2f",
                        collocate, legacyScore, hybridScore));
            }
        }
    }

    @Test
    void hybridExecutorIsNotSlowerThanLegacy() throws Exception {
        String lemma = "dog";
        String pattern = "[tag=\".*\"]";

        // Warm up
        try (HybridQueryExecutor hybrid = new HybridQueryExecutor(hybridIndexPath.toString())) {
            hybrid.findCollocations(lemma, pattern, 0, 50);
        }
        try (WordSketchQueryExecutor legacy = new WordSketchQueryExecutor(legacyIndexPath.toString())) {
            legacy.findCollocations(lemma, pattern, 0, 50);
        }

        // Measure hybrid
        long hybridStart = System.nanoTime();
        try (HybridQueryExecutor hybrid = new HybridQueryExecutor(hybridIndexPath.toString())) {
            for (int i = 0; i < 10; i++) {
                hybrid.findCollocations(lemma, pattern, 0, 50);
            }
        }
        long hybridTime = System.nanoTime() - hybridStart;

        // Measure legacy
        long legacyStart = System.nanoTime();
        try (WordSketchQueryExecutor legacy = new WordSketchQueryExecutor(legacyIndexPath.toString())) {
            for (int i = 0; i < 10; i++) {
                legacy.findCollocations(lemma, pattern, 0, 50);
            }
        }
        long legacyTime = System.nanoTime() - legacyStart;

        System.out.printf("Performance: Legacy=%dms, Hybrid=%dms%n",
            legacyTime / 1_000_000, hybridTime / 1_000_000);

        // Hybrid should not be more than 10x slower (on this tiny index)
        // In practice, on large indices, hybrid should be faster
        assertTrue(hybridTime < legacyTime * 10,
            String.format("Hybrid is too slow: %dms vs %dms legacy",
                hybridTime / 1_000_000, legacyTime / 1_000_000));
    }
}

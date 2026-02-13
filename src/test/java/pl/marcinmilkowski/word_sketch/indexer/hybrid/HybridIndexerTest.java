package pl.marcinmilkowski.word_sketch.indexer.hybrid;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HybridIndexer.
 */
class HybridIndexerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Index single sentence")
    void indexSingleSentence() throws IOException {
        Path indexPath = tempDir.resolve("index");
        
        try (HybridIndexer indexer = new HybridIndexer(indexPath.toString())) {
            SentenceDocument sentence = SentenceDocument.builder()
                .sentenceId(1)
                .text("The cat sat on the mat.")
                .addToken(0, "The", "the", "DT", 0, 3)
                .addToken(1, "cat", "cat", "NN", 4, 7)
                .addToken(2, "sat", "sit", "VBD", 8, 11)
                .addToken(3, "on", "on", "IN", 12, 14)
                .addToken(4, "the", "the", "DT", 15, 18)
                .addToken(5, "mat", "mat", "NN", 19, 22)
                .addToken(6, ".", ".", ".", 22, 23)
                .build();
            
            indexer.indexSentence(sentence);
            indexer.commit();
            
            assertEquals(1, indexer.getSentenceCount());
            assertEquals(7, indexer.getTokenCount());
        }
        
        // Verify index contents
        try (IndexReader reader = DirectoryReader.open(FSDirectory.open(indexPath))) {
            assertEquals(1, reader.numDocs());
            
            IndexSearcher searcher = new IndexSearcher(reader);
            
            // Search for lemma "cat"
            TopDocs results = searcher.search(new TermQuery(new Term("lemma", "cat")), 10);
            assertEquals(1, results.totalHits.value());
            
            // Search for lemma "sit" (lemma of "sat")
            results = searcher.search(new TermQuery(new Term("lemma", "sit")), 10);
            assertEquals(1, results.totalHits.value());
        }
    }

    @Test
    @DisplayName("Index writes lemma_ids DocValues and lexicon.bin")
    void indexWritesLemmaIdsAndLexicon() throws IOException {
        Path indexPath = tempDir.resolve("index");
        Path statsPath = indexPath.resolve("stats.tsv");

        try (HybridIndexer indexer = new HybridIndexer(indexPath.toString())) {
            SentenceDocument sentence = SentenceDocument.builder()
                .sentenceId(1)
                .text("The cat sat on the mat.")
                .addToken(0, "The", "the", "DT", 0, 3)
                .addToken(1, "cat", "cat", "NN", 4, 7)
                .addToken(2, "sat", "sit", "VBD", 8, 11)
                .addToken(3, "on", "on", "IN", 12, 14)
                .addToken(4, "the", "the", "DT", 15, 18)
                .addToken(5, "mat", "mat", "NN", 19, 22)
                .addToken(6, ".", ".", ".", 22, 23)
                .build();

            indexer.indexSentence(sentence);
            indexer.commit();
            indexer.writeStatistics(statsPath.toString());
        }

        // Verify lemma_ids DocValues exist
        try (IndexReader reader = DirectoryReader.open(FSDirectory.open(indexPath))) {
            assertEquals(1, reader.numDocs());

            var leaf = reader.leaves().get(0);
            var dv = leaf.reader().getBinaryDocValues("lemma_ids");
            assertNotNull(dv);
            assertTrue(dv.advanceExact(0));

            int[] ids = LemmaIdsCodec.decode(dv.binaryValue());
            assertEquals(7, ids.length);
        }

        // Verify lexicon is written and readable
        Path lexiconPath = indexPath.resolve("lexicon.bin");
        assertTrue(Files.exists(lexiconPath));

        try (LemmaLexiconReader lex = new LemmaLexiconReader(lexiconPath.toString())) {
            assertTrue(lex.size() >= 6);
            assertTrue(lex.getTotalTokens() >= 7);
        }
    }

    @Test
    @DisplayName("Index multiple sentences")
    void indexMultipleSentences() throws IOException {
        Path indexPath = tempDir.resolve("index");
        
        try (HybridIndexer indexer = new HybridIndexer(indexPath.toString())) {
            // Sentence 1
            indexer.indexSentence(SentenceDocument.builder()
                .sentenceId(1)
                .text("The dog runs.")
                .addToken(0, "The", "the", "DT", 0, 3)
                .addToken(1, "dog", "dog", "NN", 4, 7)
                .addToken(2, "runs", "run", "VBZ", 8, 12)
                .addToken(3, ".", ".", ".", 12, 13)
                .build());
            
            // Sentence 2
            indexer.indexSentence(SentenceDocument.builder()
                .sentenceId(2)
                .text("The cat sleeps.")
                .addToken(0, "The", "the", "DT", 0, 3)
                .addToken(1, "cat", "cat", "NN", 4, 7)
                .addToken(2, "sleeps", "sleep", "VBZ", 8, 14)
                .addToken(3, ".", ".", ".", 14, 15)
                .build());
            
            // Sentence 3
            indexer.indexSentence(SentenceDocument.builder()
                .sentenceId(3)
                .text("Dogs and cats are friends.")
                .addToken(0, "Dogs", "dog", "NNS", 0, 4)
                .addToken(1, "and", "and", "CC", 5, 8)
                .addToken(2, "cats", "cat", "NNS", 9, 13)
                .addToken(3, "are", "be", "VBP", 14, 17)
                .addToken(4, "friends", "friend", "NNS", 18, 25)
                .addToken(5, ".", ".", ".", 25, 26)
                .build());
            
            indexer.commit();
            
            assertEquals(3, indexer.getSentenceCount());
            assertEquals(14, indexer.getTokenCount());
        }
        
        // Verify index
        try (IndexReader reader = DirectoryReader.open(FSDirectory.open(indexPath))) {
            assertEquals(3, reader.numDocs());
            
            IndexSearcher searcher = new IndexSearcher(reader);
            
            // "dog" appears in sentence 1 and 3
            TopDocs results = searcher.search(new TermQuery(new Term("lemma", "dog")), 10);
            assertEquals(2, results.totalHits.value());
            
            // "cat" appears in sentence 2 and 3
            results = searcher.search(new TermQuery(new Term("lemma", "cat")), 10);
            assertEquals(2, results.totalHits.value());
            
            // "the" appears in sentences 1 and 2
            results = searcher.search(new TermQuery(new Term("lemma", "the")), 10);
            assertEquals(2, results.totalHits.value());
        }
    }

    @Test
    @DisplayName("Statistics collector tracks frequencies")
    void statisticsCollector() throws IOException {
        Path indexPath = tempDir.resolve("index");
        
        try (HybridIndexer indexer = new HybridIndexer(indexPath.toString())) {
            indexer.indexSentence(SentenceDocument.builder()
                .sentenceId(1)
                .text("The big dog and the small dog.")
                .addToken(0, "The", "the", "DT", 0, 3)
                .addToken(1, "big", "big", "JJ", 4, 7)
                .addToken(2, "dog", "dog", "NN", 8, 11)
                .addToken(3, "and", "and", "CC", 12, 15)
                .addToken(4, "the", "the", "DT", 16, 19)
                .addToken(5, "small", "small", "JJ", 20, 25)
                .addToken(6, "dog", "dog", "NN", 26, 29)
                .addToken(7, ".", ".", ".", 29, 30)
                .build());
            
            indexer.commit();
            
            var stats = indexer.getStatisticsCollector();
            
            // "dog" appears twice
            assertEquals(2, stats.getLemmaFrequency("dog"));
            
            // "the" appears twice
            assertEquals(2, stats.getLemmaFrequency("the"));
            
            // "big" appears once
            assertEquals(1, stats.getLemmaFrequency("big"));
            
            // Check totals
            assertEquals(8, stats.getTotalTokens());
            assertEquals(1, stats.getTotalSentences());
        }
    }

    @Test
    @DisplayName("DocValues store token sequences")
    void docValuesStoreTokens() throws IOException {
        Path indexPath = tempDir.resolve("index");
        
        try (HybridIndexer indexer = new HybridIndexer(indexPath.toString())) {
            indexer.indexSentence(SentenceDocument.builder()
                .sentenceId(1)
                .text("Hello world!")
                .addToken(0, "Hello", "hello", "UH", 0, 5)
                .addToken(1, "world", "world", "NN", 6, 11)
                .addToken(2, "!", "!", ".", 11, 12)
                .build());
            
            indexer.commit();
        }
        
        // Read back tokens via DocValues
        try (IndexReader reader = DirectoryReader.open(FSDirectory.open(indexPath))) {
            var leafReader = reader.leaves().get(0).reader();
            var binaryDocValues = leafReader.getBinaryDocValues("tokens");
            
            assertTrue(binaryDocValues.advanceExact(0));
            var bytesRef = binaryDocValues.binaryValue();
            
            List<SentenceDocument.Token> tokens = TokenSequenceCodec.decode(bytesRef);
            assertEquals(3, tokens.size());
            assertEquals("Hello", tokens.get(0).word());
            assertEquals("hello", tokens.get(0).lemma());
            assertEquals("world", tokens.get(1).word());
            assertEquals("!", tokens.get(2).word());
        }
    }

    @Test
    @DisplayName("Write and read statistics file")
    void writeAndReadStatistics() throws IOException {
        Path indexPath = tempDir.resolve("index");
        Path statsPath = tempDir.resolve("stats.tsv");
        
        try (HybridIndexer indexer = new HybridIndexer(indexPath.toString())) {
            indexer.indexSentence(SentenceDocument.builder()
                .sentenceId(1)
                .text("The quick brown fox.")
                .addToken(0, "The", "the", "DT", 0, 3)
                .addToken(1, "quick", "quick", "JJ", 4, 9)
                .addToken(2, "brown", "brown", "JJ", 10, 15)
                .addToken(3, "fox", "fox", "NN", 16, 19)
                .addToken(4, ".", ".", ".", 19, 20)
                .build());
            
            indexer.commit();
            indexer.writeStatistics(statsPath.toString());
        }
        
        // Verify statistics file exists and contains data
        assertTrue(statsPath.toFile().exists());
        assertTrue(statsPath.toFile().length() > 0);
    }

    @Test
    @DisplayName("POS group field is indexed")
    void posGroupFieldIndexed() throws IOException {
        Path indexPath = tempDir.resolve("index");
        
        try (HybridIndexer indexer = new HybridIndexer(indexPath.toString())) {
            indexer.indexSentence(SentenceDocument.builder()
                .sentenceId(1)
                .text("The cat runs quickly.")
                .addToken(0, "The", "the", "DT", 0, 3)
                .addToken(1, "cat", "cat", "NOUN", 4, 7)  // NOUN -> pos_group "noun"
                .addToken(2, "runs", "run", "VERB", 8, 12) // VERB -> pos_group "verb"
                .addToken(3, "quickly", "quickly", "ADV", 13, 20) // ADV -> pos_group "adv"
                .addToken(4, ".", ".", "PUNCT", 20, 21)
                .build());
            
            indexer.commit();
        }
        
        try (IndexReader reader = DirectoryReader.open(FSDirectory.open(indexPath))) {
            IndexSearcher searcher = new IndexSearcher(reader);
            
            // Search by pos_group "noun"
            TopDocs results = searcher.search(new TermQuery(new Term("pos_group", "noun")), 10);
            assertEquals(1, results.totalHits.value());
            
            // Search by pos_group "verb"
            results = searcher.search(new TermQuery(new Term("pos_group", "verb")), 10);
            assertEquals(1, results.totalHits.value());
        }
    }

    @Test
    @DisplayName("Lemma field is normalized to lowercase")
    void lemmaFieldIsLowercased() throws IOException {
        Path indexPath = tempDir.resolve("index");

        try (HybridIndexer indexer = new HybridIndexer(indexPath.toString())) {
            indexer.indexSentence(SentenceDocument.builder()
                .sentenceId(1)
                .text("NASA launches rockets.")
                .addToken(0, "NASA", "NASA", "PROPN", 0, 4)
                .addToken(1, "launches", "launch", "VERB", 5, 13)
                .addToken(2, "rockets", "rocket", "NOUN", 14, 21)
                .addToken(3, ".", ".", "PUNCT", 21, 22)
                .build());

            indexer.commit();
        }

        try (IndexReader reader = DirectoryReader.open(FSDirectory.open(indexPath))) {
            IndexSearcher searcher = new IndexSearcher(reader);

            TopDocs lower = searcher.search(new TermQuery(new Term("lemma", "nasa")), 10);
            assertEquals(1, lower.totalHits.value());

            TopDocs upper = searcher.search(new TermQuery(new Term("lemma", "NASA")), 10);
            assertEquals(0, upper.totalHits.value());
        }
    }

    @Test
    @DisplayName("Empty sentence is handled")
    void emptyTokenList() throws IOException {
        Path indexPath = tempDir.resolve("index");
        
        try (HybridIndexer indexer = new HybridIndexer(indexPath.toString())) {
            indexer.indexSentence(SentenceDocument.builder()
                .sentenceId(1)
                .text("")
                .build());
            
            indexer.commit();
            assertEquals(1, indexer.getSentenceCount());
            assertEquals(0, indexer.getTokenCount());
        }
    }
}

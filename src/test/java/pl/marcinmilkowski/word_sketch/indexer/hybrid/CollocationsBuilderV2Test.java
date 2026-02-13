package pl.marcinmilkowski.word_sketch.indexer.hybrid;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.MMapDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CollocationsBuilderV2Test {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("V2 builder produces collocations.bin on tiny corpus")
    void buildsTinyCorpus() throws Exception {
        Path indexPath = tempDir.resolve("index");
        Path statsPath = indexPath.resolve("stats.tsv");
        Path outPath = tempDir.resolve("collocations.bin");

        try (HybridIndexer indexer = new HybridIndexer(indexPath.toString())) {
            indexer.indexSentence(SentenceDocument.builder()
                .sentenceId(1)
                .text("a b a")
                .addToken(0, "a", "a", "X", 0, 1)
                .addToken(1, "b", "b", "X", 2, 3)
                .addToken(2, "a", "a", "X", 4, 5)
                .build());
            indexer.commit();
            indexer.writeStatistics(statsPath.toString());
        }

        CollocationsBuilderV2 b = new CollocationsBuilderV2(indexPath.toString());
        b.setWindowSize(1);
        b.setTopK(10);
        b.setMinHeadwordFrequency(1);
        b.setMinCooccurrence(1);
        b.setNumShards(2);
        b.setSpillThresholdPerShard(10); // force frequent spills on tiny map
        b.build(outPath.toString());

        try (CollocationsReader r = new CollocationsReader(outPath.toString())) {
            CollocationEntry a = r.getCollocations("a");
            assertNotNull(a);
            assertEquals(2, a.headwordFrequency());
            assertFalse(a.collocates().isEmpty());

            Collocation top = a.collocates().get(0);
            assertEquals("b", top.lemma());
            assertEquals(2, top.cooccurrence());
            assertEquals(1, top.frequency());
            assertTrue(top.logDice() > 0);
        }
    }

    @Test
    @DisplayName("V2 builder fails fast when a segment misses lemma_ids")
    void failsWhenLemmaIdsMissingInAnySegment() throws Exception {
        Path indexPath = tempDir.resolve("index-missing-lemma-ids");
        Path statsPath = indexPath.resolve("stats.tsv");
        Path outPath = tempDir.resolve("collocations-missing-lemma-ids.bin");

        try (HybridIndexer indexer = new HybridIndexer(indexPath.toString())) {
            indexer.indexSentence(SentenceDocument.builder()
                .sentenceId(1)
                .text("alpha beta")
                .addToken(0, "alpha", "alpha", "NN", 0, 5)
                .addToken(1, "beta", "beta", "NN", 6, 10)
                .build());
            indexer.commit();
            indexer.writeStatistics(statsPath.toString());
        }

        try (IndexWriter writer = new IndexWriter(
            MMapDirectory.open(indexPath),
            new IndexWriterConfig(new WhitespaceAnalyzer()))) {
            Document brokenDoc = new Document();
            brokenDoc.add(new TextField("lemma", "broken", Field.Store.NO));
            writer.addDocument(brokenDoc);
            writer.commit();
        }

        CollocationsBuilderV2 b = new CollocationsBuilderV2(indexPath.toString());
        b.setWindowSize(1);
        b.setTopK(10);
        b.setMinHeadwordFrequency(1);
        b.setMinCooccurrence(1);
        b.setNumShards(2);

        IOException ex = assertThrows(IOException.class, () -> b.build(outPath.toString()));
        assertTrue(ex.getMessage().contains("lemma_ids"));
    }
}

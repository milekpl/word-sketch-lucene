package pl.marcinmilkowski.word_sketch.indexer;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic test: check whether problematic lemmas exist in the `index/` and print sample sentences.
 */
public class IndexLemmaPresenceTest {

    @Test
    void findProblematicLemmas() throws IOException {
        Path idx = Paths.get("index");
        if (!idx.toFile().exists()) {
            System.out.println("index/ not found — skipping presence test");
            return;
        }

        try (var dir = FSDirectory.open(idx);
             var reader = DirectoryReader.open(dir)) {
            IndexSearcher searcher = new IndexSearcher(reader);

            String[] lemmas = new String[]{"11899554", "10.1080/02640414.2012.712714", "goldberger", "phonon", "pre-nuclear"};

            for (String lemma : lemmas) {
                TermQuery tq = new TermQuery(new Term("lemma", lemma.toLowerCase()));
                TopDocs td = searcher.search(tq, 5);
                System.out.printf("Lemma '%s' — hits=%d\n", lemma, td.totalHits.value());
                if (td.totalHits.value() > 0) {
                    for (var sd : td.scoreDocs) {
                        var doc = searcher.storedFields().document(sd.doc);
                        System.out.println("  doc_id=" + doc.get("doc_id") + " text=" + doc.get("text"));
                    }
                }
            }
        }
    }
}

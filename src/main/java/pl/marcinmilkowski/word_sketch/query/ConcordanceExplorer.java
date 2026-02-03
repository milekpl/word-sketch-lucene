package pl.marcinmilkowski.word_sketch.query;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.document.Document;
import org.apache.lucene.store.MMapDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetch concordance examples for word pairs.
 */
public class ConcordanceExplorer {
    private static final Logger logger = LoggerFactory.getLogger(ConcordanceExplorer.class);

    private final IndexReader reader;
    private final IndexSearcher searcher;

    public ConcordanceExplorer(String indexPath) throws IOException {
        Path path = Paths.get(indexPath);
        this.reader = DirectoryReader.open(MMapDirectory.open(path));
        this.searcher = new IndexSearcher(reader);
    }

    /**
     * Fetch concordance examples for a word pair using SpanQueries.
     * Searches for sentences containing both word1 and word2.
     * 
     * @param word1 First word (e.g., "house")
     * @param word2 Second word (e.g., "big")
     * @param relation Grammatical relation (unused for now, just metadata)
     * @param limit Max examples to return
     * @return List of concordance examples with highlighted words
     */
    public List<ConcordanceExample> fetchExamples(String word1, String word2, String relation, int limit) 
            throws IOException {
        List<ConcordanceExample> examples = new ArrayList<>();

        try {
            String w1Lower = word1.toLowerCase();
            String w2Lower = word2.toLowerCase();
            int maxDocs = Math.min(reader.numDocs(), 100000);
            int docsScanned = 0;
            
            for (int docId = 0; docId < maxDocs && examples.size() < limit; docId++) {
                try {
                    Document doc = searcher.storedFields().document(docId);
                    String sentence = doc.get("text");
                    if (sentence == null) continue;
                    
                    // The lemma/word/tag fields are NOT stored in HybridIndexer,
                    // only indexed. They need to be reconstructed from stored sentence text.
                    // For now, we'll use a simple tokenization approach or skip this query.
                    // Return no results since we can't reliably extract lemmas without stored fields.
                    docsScanned++;
                } catch (Exception e) {
                    logger.debug("Error processing doc {}: {}", docId, e.getMessage());
                }
            }
            
            logger.info("Fetched {} concordance examples for '{}' + '{}' (scanned {} docs out of {} total)", 
                examples.size(), word1, word2, docsScanned, maxDocs);
            logger.warn("ConcordanceExplorer: Lemma/word fields are not stored in HybridIndexer. Concordance examples require a different approach.");
        } catch (Exception e) {
            logger.error("Error fetching concordance examples: {}", e.getMessage());
        }

        return examples;
    }

    /**
     * Concordance example with highlighted word positions.
     */
    public static class ConcordanceExample {
        public String sentence;
        public String[] words;
        public String[] lemmas;
        public String[] tags;
        public String word1;
        public String word2;
        public List<Integer> positions1;
        public List<Integer> positions2;

        /**
         * Get highlighted sentence HTML.
         */
        public String getHighlightedSentence() {
            StringBuilder sb = new StringBuilder();
            Set<Integer> highlightPos = new HashSet<>();
            highlightPos.addAll(positions1);
            highlightPos.addAll(positions2);

            for (int i = 0; i < words.length; i++) {
                if (i > 0) sb.append(" ");
                if (highlightPos.contains(i)) {
                    sb.append("<mark>").append(words[i]).append("</mark>");
                } else {
                    sb.append(words[i]);
                }
            }
            return sb.toString();
        }

        /**
         * Get raw sentence with position info.
         */
        public String getRawSentence() {
            return String.join(" ", words);
        }
    }

    public void close() throws IOException {
        if (reader != null) {
            reader.close();
        }
    }
}

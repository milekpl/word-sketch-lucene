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
            
            for (int docId = 0; docId < maxDocs && examples.size() < limit; docId++) {
                try {
                    Document doc = searcher.storedFields().document(docId);
                    String sentence = doc.get("sentence");
                    if (sentence == null) continue;
                    
                    String lemmas = doc.get("lemma");
                    String words = doc.get("word");
                    String tags = doc.get("tag");
                    
                    if (lemmas == null || words == null || tags == null) continue;

                    String[] lemmaArray = lemmas.split("\\|");
                    String[] wordArray = words.split("\\|");
                    String[] tagArray = tags.split("\\|");

                    if (lemmaArray.length != wordArray.length || wordArray.length != tagArray.length) {
                        continue;
                    }

                    List<Integer> pos1 = new ArrayList<>();
                    List<Integer> pos2 = new ArrayList<>();

                    for (int i = 0; i < lemmaArray.length; i++) {
                        String lemma = lemmaArray[i].toLowerCase();
                        if (lemma.equals(w1Lower)) {
                            pos1.add(i);
                        }
                        if (lemma.equals(w2Lower)) {
                            pos2.add(i);
                        }
                    }

                    if (!pos1.isEmpty() && !pos2.isEmpty()) {
                        ConcordanceExample ex = new ConcordanceExample();
                        ex.sentence = sentence;
                        ex.words = wordArray;
                        ex.lemmas = lemmaArray;
                        ex.tags = tagArray;
                        ex.word1 = word1;
                        ex.word2 = word2;
                        ex.positions1 = pos1;
                        ex.positions2 = pos2;
                        examples.add(ex);
                    }
                } catch (Exception e) {
                    logger.debug("Error processing doc {}: {}", docId, e.getMessage());
                }
            }
            
            logger.info("Fetched {} concordance examples for '{}' + '{}' (scanned {} docs)", 
                examples.size(), word1, word2, maxDocs);
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

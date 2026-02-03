package pl.marcinmilkowski.word_sketch.query;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.queries.spans.*;
import org.apache.lucene.document.Document;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pl.marcinmilkowski.word_sketch.indexer.hybrid.TokenSequenceCodec;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.SentenceDocument;

/**
 * Fetch concordance examples for word pairs using SpanQueries.
 * 
 * Uses the indexed lemma field (with positions) to find sentences containing
 * both words, then decodes the tokens BinaryDocValues to get actual word forms.
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
     * Searches for sentences containing both word1 and word2 within a window.
     * 
     * @param word1 First word (lemma)
     * @param word2 Second word (lemma)
     * @param relation Grammatical relation (metadata, not used for query)
     * @param limit Max examples to return
     * @return List of concordance examples with highlighted words
     */
    public List<ConcordanceExample> fetchExamples(String word1, String word2, String relation, int limit) 
            throws IOException {
        List<ConcordanceExample> examples = new ArrayList<>();

        try {
            String w1Lower = word1.toLowerCase();
            String w2Lower = word2.toLowerCase();
            
            // Build SpanNearQuery: both lemmas within 10 words of each other
            SpanTermQuery span1 = new SpanTermQuery(new Term("lemma", w1Lower));
            SpanTermQuery span2 = new SpanTermQuery(new Term("lemma", w2Lower));
            
            // Allow either order, within 10 words
            SpanNearQuery nearQuery = SpanNearQuery.newUnorderedNearQuery("lemma")
                .addClause(span1)
                .addClause(span2)
                .setSlop(10)
                .build();
            
            // Search for matching documents
            TopDocs topDocs = searcher.search(nearQuery, limit * 2); // Get extra in case some fail
            
            logger.debug("SpanNearQuery for '{}' + '{}' found {} hits", w1Lower, w2Lower, topDocs.totalHits.value());
            
            for (ScoreDoc hit : topDocs.scoreDocs) {
                if (examples.size() >= limit) break;
                
                try {
                    ConcordanceExample example = extractExample(hit.doc, w1Lower, w2Lower, word1, word2);
                    if (example != null) {
                        examples.add(example);
                    }
                } catch (Exception e) {
                    logger.debug("Error extracting example from doc {}: {}", hit.doc, e.getMessage());
                }
            }
            
            logger.info("Fetched {} concordance examples for '{}' + '{}'", examples.size(), word1, word2);
            
        } catch (Exception e) {
            logger.error("Error fetching concordance examples: {}", e.getMessage(), e);
        }

        return examples;
    }

    /**
     * Extract a concordance example from a document.
     */
    private ConcordanceExample extractExample(int docId, String lemma1Lower, String lemma2Lower, 
                                               String word1, String word2) throws IOException {
        // Get stored sentence text
        Document doc = searcher.storedFields().document(docId);
        String sentenceText = doc.get("text");
        if (sentenceText == null) return null;
        
        // Decode tokens from BinaryDocValues
        List<SentenceDocument.Token> tokens = getTokens(docId);
        if (tokens == null || tokens.isEmpty()) return null;
        
        // Find positions of both lemmas
        List<Integer> pos1 = new ArrayList<>();
        List<Integer> pos2 = new ArrayList<>();
        String[] words = new String[tokens.size()];
        String[] lemmas = new String[tokens.size()];
        String[] tags = new String[tokens.size()];
        
        for (int i = 0; i < tokens.size(); i++) {
            SentenceDocument.Token token = tokens.get(i);
            words[i] = token.word();
            lemmas[i] = token.lemma();
            tags[i] = token.tag();
            
            if (token.lemma() != null && token.lemma().toLowerCase().equals(lemma1Lower)) {
                pos1.add(i);
            }
            if (token.lemma() != null && token.lemma().toLowerCase().equals(lemma2Lower)) {
                pos2.add(i);
            }
        }
        
        // Must have both words
        if (pos1.isEmpty() || pos2.isEmpty()) {
            return null;
        }
        
        ConcordanceExample example = new ConcordanceExample();
        example.sentence = sentenceText;
        example.words = words;
        example.lemmas = lemmas;
        example.tags = tags;
        example.word1 = word1;
        example.word2 = word2;
        example.positions1 = pos1;
        example.positions2 = pos2;
        
        return example;
    }

    /**
     * Get tokens from BinaryDocValues for a document.
     */
    private List<SentenceDocument.Token> getTokens(int docId) throws IOException {
        // Find the leaf reader containing this doc
        for (LeafReaderContext ctx : reader.leaves()) {
            int base = ctx.docBase;
            int maxDoc = ctx.reader().maxDoc();
            
            if (docId >= base && docId < base + maxDoc) {
                int localDocId = docId - base;
                BinaryDocValues tokensDV = ctx.reader().getBinaryDocValues("tokens");
                
                if (tokensDV != null && tokensDV.advanceExact(localDocId)) {
                    BytesRef bytesRef = tokensDV.binaryValue();
                    return TokenSequenceCodec.decode(bytesRef);
                }
                break;
            }
        }
        return null;
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

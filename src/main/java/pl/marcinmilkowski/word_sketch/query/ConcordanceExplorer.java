package pl.marcinmilkowski.word_sketch.query;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.queries.spans.*;
import org.apache.lucene.document.Document;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pl.marcinmilkowski.word_sketch.indexer.hybrid.TokenSequenceCodec;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.SentenceDocument;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader;

/**
 * Fetch concordance examples for word pairs using SpanQueries.
 * 
 * Uses the indexed lemma field (with positions) to find sentences containing
 * both words, then decodes the tokens BinaryDocValues to get actual word forms.
 */
public class ConcordanceExplorer {
    private static final Logger logger = LoggerFactory.getLogger(ConcordanceExplorer.class);
    private static final int DEFAULT_SLOP = 10;

    private static final String FIELD_LEMMA = "lemma";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_TOKENS = "tokens";

    private final IndexReader reader;
    private final IndexSearcher searcher;
    private final GrammarConfigLoader grammarConfig;

    public ConcordanceExplorer(String indexPath, GrammarConfigLoader grammarConfig) throws IOException {
        if (grammarConfig == null) {
            throw new IllegalArgumentException("GrammarConfigLoader is required");
        }
        Path path = Paths.get(indexPath);
        this.reader = DirectoryReader.open(FSDirectory.open(path));
        this.searcher = new IndexSearcher(reader);
        this.grammarConfig = grammarConfig;
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
    public List<ConcordanceExample> fetchExamples(String word1,
                                                  String word2,
                                                  String relation,
                                                  String expectedCollocatePos,
                                                  int limit)
            throws IOException {
        List<ConcordanceExample> examples = new ArrayList<>();
        RelationSpec relationSpec = resolveRelationSpec(relation, expectedCollocatePos);

        try {
            String w1Lower = word1.toLowerCase();
            String w2Lower = word2.toLowerCase();
            
            // Build SpanNearQuery: both lemmas within 10 words of each other
            SpanTermQuery span1 = new SpanTermQuery(new Term(FIELD_LEMMA, w1Lower));
            SpanTermQuery span2 = new SpanTermQuery(new Term(FIELD_LEMMA, w2Lower));
            
            // Allow either order, within slop
            SpanNearQuery nearQuery = SpanNearQuery.newUnorderedNearQuery(FIELD_LEMMA)
                .addClause(span1)
                .addClause(span2)
                .setSlop(relationSpec.slop)
                .build();
            
            // Search for matching documents
            TopDocs topDocs = searcher.search(nearQuery, limit * 2); // get extra in case some fail
            
            logger.debug("SpanNearQuery for '{}' + '{}' found {} hits", w1Lower, w2Lower, topDocs.totalHits.value());
            
            for (ScoreDoc hit : topDocs.scoreDocs) {
                if (examples.size() >= limit) break;

                Optional<ConcordanceExample> maybe = tryExtractExample(hit.doc,
                    w1Lower, w2Lower, word1, word2, relationSpec);
                maybe.ifPresent(examples::add);
            }
            
            logger.info("Fetched {} concordance examples for '{}' + '{}'", examples.size(), word1, word2);
            
        } catch (Exception e) {
            logger.error("Error fetching concordance examples: {}", e.getMessage(), e);
        }

        return examples;
    }

    /**
     * Helper that calls {@link #extractExample} and catches exceptions so callers can remain simple.
     */
    private Optional<ConcordanceExample> tryExtractExample(int docId,
                                                           String lemma1Lower,
                                                           String lemma2Lower,
                                                           String word1,
                                                           String word2,
                                                           RelationSpec relationSpec) {
        try {
            ConcordanceExample example = extractExample(docId, lemma1Lower, lemma2Lower, word1, word2, relationSpec);
            return Optional.ofNullable(example);
        } catch (Exception e) {
            logger.debug("Error extracting example from doc {}: {}", docId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Extract a concordance example from a document.
     */
    private ConcordanceExample extractExample(int docId, String lemma1Lower, String lemma2Lower,
                                              String word1, String word2, RelationSpec relationSpec)
            throws IOException {
        // Get stored sentence text
        Document doc = searcher.storedFields().document(docId);
        String sentenceText = doc.get("text");
        if (sentenceText == null) return null;
        
        // Decode tokens from BinaryDocValues
        List<SentenceDocument.Token> tokens = getTokens(docId);
        if (tokens.isEmpty()) return null;

        // Find positions of both lemmas and populate arrays
        List<Integer> headPositions = new ArrayList<>();
        List<Integer> collocatePositions = new ArrayList<>();
        String[] words = new String[tokens.size()];
        String[] lemmas = new String[tokens.size()];
        String[] tags = new String[tokens.size()];

        populateTokenArrays(tokens, lemma1Lower, lemma2Lower, words, lemmas, tags, headPositions, collocatePositions);
        
        // Must have both words
        if (headPositions.isEmpty() || collocatePositions.isEmpty()) {
            return null;
        }

        List<Integer> matchedHeadPositions = new ArrayList<>();
        List<Integer> matchedCollocatePositions = new ArrayList<>();
        for (Integer headPos : headPositions) {
            for (Integer collocatePos : collocatePositions) {
                if (matchesRelation(headPos, collocatePos, tags, lemmas, relationSpec)) {
                    if (!matchedHeadPositions.contains(headPos)) matchedHeadPositions.add(headPos);
                    if (!matchedCollocatePositions.contains(collocatePos)) matchedCollocatePositions.add(collocatePos);
                }
            }
        }

        if (matchedHeadPositions.isEmpty() || matchedCollocatePositions.isEmpty()) {
            return null;
        }

        return new ConcordanceExample(sentenceText, words, lemmas, tags, word1, word2, matchedHeadPositions, matchedCollocatePositions);
    }

    private RelationSpec resolveRelationSpec(String relation, String expectedCollocatePos) {
        String normalizedPos = normalizePos(expectedCollocatePos);

        if (relation == null || relation.isBlank()) {
            return new RelationSpec(null, normalizedPos, DEFAULT_SLOP, null, false, false);
        }

        String rel = relation.trim().toLowerCase(Locale.ROOT);
        return switch (rel) {
            case "noun_compounds" -> new RelationSpec("nn.*", firstNonBlank(normalizedPos, "nn.*"), 1, false, true, false);
            case "noun_adj_predicates" -> new RelationSpec("nn.*", firstNonBlank(normalizedPos, "jj.*"), 8, null, false, true);
            case "noun_modifiers" -> new RelationSpec("nn.*", firstNonBlank(normalizedPos, "jj.*"), 2, true, false, false);
            case "noun_verbs" -> new RelationSpec("nn.*", firstNonBlank(normalizedPos, "vb.*"), DEFAULT_SLOP, null, false, false);
            case "noun_prepositions" -> new RelationSpec("nn.*", firstNonBlank(normalizedPos, "in"), DEFAULT_SLOP, null, false, false);
            case "noun_adverbs" -> new RelationSpec("nn.*", firstNonBlank(normalizedPos, "rb.*"), DEFAULT_SLOP, null, false, false);
            case "noun_possessives" -> new RelationSpec("nn.*", firstNonBlank(normalizedPos, "pos"), 2, null, false, false);

            case "verb_nouns" -> new RelationSpec("vb.*", firstNonBlank(normalizedPos, "nn.*"), DEFAULT_SLOP, null, false, false);
            case "verb_particles" -> new RelationSpec("vb.*", firstNonBlank(normalizedPos, "rp"), 3, null, false, false);
            case "verb_prepositions" -> new RelationSpec("vb.*", firstNonBlank(normalizedPos, "in"), DEFAULT_SLOP, null, false, false);
            case "verb_adverbs" -> new RelationSpec("vb.*", firstNonBlank(normalizedPos, "rb.*"), DEFAULT_SLOP, null, false, false);
            case "verb_adjectives" -> new RelationSpec("vb.*", firstNonBlank(normalizedPos, "jj.*"), DEFAULT_SLOP, null, false, false);
            case "verb_to" -> new RelationSpec("vb.*", firstNonBlank(normalizedPos, "to"), 3, null, false, false);
            case "verb_verbs" -> new RelationSpec("vb.*", firstNonBlank(normalizedPos, "vb.*"), DEFAULT_SLOP, null, false, false);

            case "adj_nouns" -> new RelationSpec("jj.*", firstNonBlank(normalizedPos, "nn.*"), 3, false, false, false);
            case "adj_adverbs" -> new RelationSpec("jj.*", firstNonBlank(normalizedPos, "rb.*"), 3, true, false, false);
            case "adj_verbs" -> new RelationSpec("jj.*", firstNonBlank(normalizedPos, "vb.*"), DEFAULT_SLOP, null, false, false);
            case "adj_prepositions" -> new RelationSpec("jj.*", firstNonBlank(normalizedPos, "in"), 3, false, false, false);
            case "adj_coordinated" -> new RelationSpec("jj.*", firstNonBlank(normalizedPos, "jj.*"), DEFAULT_SLOP, null, false, false);

            default -> new RelationSpec(null, normalizedPos, DEFAULT_SLOP, null, false, false);
        };
    }

    private String normalizePos(String expectedCollocatePos) {
        if (expectedCollocatePos == null) {
            return null;
        }
        String normalized = expectedCollocatePos.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private void populateTokenArrays(List<SentenceDocument.Token> tokens,
                                     String lemma1Lower,
                                     String lemma2Lower,
                                     String[] words,
                                     String[] lemmas,
                                     String[] tags,
                                     List<Integer> headPositions,
                                     List<Integer> collocatePositions) {
        for (int i = 0; i < tokens.size(); i++) {
            SentenceDocument.Token token = tokens.get(i);
            words[i] = token.word();
            lemmas[i] = token.lemma();
            tags[i] = token.tag();

            String tokenLemma = token.lemma();
            if (tokenLemma != null) {
                String lower = tokenLemma.toLowerCase(Locale.ROOT);
                if (lower.equals(lemma1Lower)) {
                    headPositions.add(i);
                }
                if (lower.equals(lemma2Lower)) {
                    collocatePositions.add(i);
                }
            }
        }
    }
    private boolean matchesRelation(int headPos,
                                    int collocatePos,
                                    String[] tags,
                                    String[] lemmas,
                                    RelationSpec relationSpec) {
        String headTag = safeTag(tags, headPos);
        String collocateTag = safeTag(tags, collocatePos);

        if (relationSpec.headTagRegex != null && !tagMatches(headTag, relationSpec.headTagRegex)) {
            return false;
        }
        if (relationSpec.collocateTagRegex != null && !tagMatches(collocateTag, relationSpec.collocateTagRegex)) {
            return false;
        }

        int distance = Math.abs(headPos - collocatePos);
        if (distance > relationSpec.slop) {
            return false;
        }

        if (relationSpec.requireAdjacent && distance != 1) {
            return false;
        }

        if (relationSpec.collocateBeforeHead != null) {
            boolean collocateBeforeHead = collocatePos < headPos;
            if (collocateBeforeHead != Boolean.TRUE.equals(relationSpec.collocateBeforeHead)) {
                return false;
            }
        }

        if (relationSpec.requireCopularBridge && !hasCopularBridge(headPos, collocatePos, tags, lemmas)) {
            return false;
        }

        return true;
    }

    private boolean hasCopularBridge(int headPos, int collocatePos, String[] tags, String[] lemmas) {
        int start = Math.min(headPos, collocatePos) + 1;
        int end = Math.max(headPos, collocatePos) - 1;
        if (start > end) {
            return false;
        }

        for (int pos = start; pos <= end; pos++) {
            String tag = safeTag(tags, pos).toUpperCase(Locale.ROOT);
            String lemma = pos >= 0 && pos < lemmas.length && lemmas[pos] != null
                ? lemmas[pos].toLowerCase(Locale.ROOT)
                : "";
            if (tag.startsWith("VB") && isCopularVerb(lemma)) {
                return true;
            }
        }

        return false;
    }

    private boolean isCopularVerb(String lemma) {
        if (lemma == null || lemma.isEmpty()) {
            return false;
        }
        return grammarConfig.isCopularVerb(lemma);
    }

    private String safeTag(String[] tags, int pos) {
        if (pos < 0 || pos >= tags.length) {
            return "";
        }
        return tags[pos] == null ? "" : tags[pos];
    }

    private boolean tagMatches(String tag, String regex) {
        return tag.toLowerCase(Locale.ROOT).matches(regex.toLowerCase(Locale.ROOT));
    }

    private static class RelationSpec {
        final String headTagRegex;
        final String collocateTagRegex;
        final int slop;
        final Boolean collocateBeforeHead;
        final boolean requireAdjacent;

        final boolean requireCopularBridge;

        RelationSpec(String headTagRegex,
                     String collocateTagRegex,
                     int slop,
                     Boolean collocateBeforeHead,
                     boolean requireAdjacent,
                     boolean requireCopularBridge) {
            this.headTagRegex = headTagRegex;
            this.collocateTagRegex = collocateTagRegex;
            this.slop = slop;
            this.collocateBeforeHead = collocateBeforeHead;
            this.requireAdjacent = requireAdjacent;
            this.requireCopularBridge = requireCopularBridge;
        }
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
                BinaryDocValues tokensDV = ctx.reader().getBinaryDocValues(FIELD_TOKENS);

                if (tokensDV != null && tokensDV.advanceExact(localDocId)) {
                    BytesRef bytesRef = tokensDV.binaryValue();
                    return TokenSequenceCodec.decode(bytesRef);
                }
                break;
            }
        }
        return Collections.emptyList();
    }

    /**
     * Concordance example with highlighted word positions.
     */
    public static class ConcordanceExample {
        private final String sentence;
        private final String[] words;
        private final String[] lemmas;
        private final String[] tags;
        private final String word1;
        private final String word2;
        private final List<Integer> positions1;
        private final List<Integer> positions2;

        public ConcordanceExample(String sentence,
                                  String[] words,
                                  String[] lemmas,
                                  String[] tags,
                                  String word1,
                                  String word2,
                                  List<Integer> positions1,
                                  List<Integer> positions2) {
            this.sentence = sentence != null ? sentence : "";
            this.words = words != null ? words : new String[0];
            this.lemmas = lemmas != null ? lemmas : new String[0];
            this.tags = tags != null ? tags : new String[0];
            this.word1 = word1;
            this.word2 = word2;
            this.positions1 = positions1 != null ? Collections.unmodifiableList(new ArrayList<>(positions1)) : Collections.emptyList();
            this.positions2 = positions2 != null ? Collections.unmodifiableList(new ArrayList<>(positions2)) : Collections.emptyList();
        }

        public String getSentence() { return sentence; }
        public String[] getWords() { return words; }
        public String[] getLemmas() { return lemmas; }
        public String[] getTags() { return tags; }
        public String getWord1() { return word1; }
        public String getWord2() { return word2; }
        public List<Integer> getPositions1() { return positions1; }
        public List<Integer> getPositions2() { return positions2; }

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

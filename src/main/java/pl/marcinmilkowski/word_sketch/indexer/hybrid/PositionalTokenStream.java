package pl.marcinmilkowski.word_sketch.indexer.hybrid;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * A TokenStream that emits tokens from a SentenceDocument with proper positions.
 * 
 * This stream is used by the HybridIndexer to index sentence documents.
 * Each token is emitted with:
 * - Its word form as the term
 * - Correct position increment (1 for normal tokens)
 * - Start/end offsets for highlighting
 * - Payload containing the lemma and tag information
 */
public final class PositionalTokenStream extends TokenStream {

    private final CharTermAttribute termAttr;
    private final PositionIncrementAttribute posIncrAttr;
    private final OffsetAttribute offsetAttr;
    private final PayloadAttribute payloadAttr;

    private List<SentenceDocument.Token> tokens;
    private int currentPosition;
    private boolean useWord = true;  // true for word field, false for lemma field
    private String fieldType = "word";  // "word", "lemma", "tag", or "pos_group"

    public PositionalTokenStream() {
        super();
        termAttr = addAttribute(CharTermAttribute.class);
        posIncrAttr = addAttribute(PositionIncrementAttribute.class);
        offsetAttr = addAttribute(OffsetAttribute.class);
        payloadAttr = addAttribute(PayloadAttribute.class);
    }

    /**
     * Sets the tokens to emit.
     * @param tokens the list of tokens from a SentenceDocument
     */
    public void setTokens(List<SentenceDocument.Token> tokens) {
        this.tokens = tokens;
        this.currentPosition = 0;
    }

    /**
     * Sets which field type this stream should emit.
     * @param fieldType one of "word", "lemma", "tag", or "pos_group"
     */
    public void setFieldType(String fieldType) {
        this.fieldType = fieldType;
    }

    @Override
    public boolean incrementToken() throws IOException {
        clearAttributes();

        if (tokens == null || currentPosition >= tokens.size()) {
            return false;
        }

        SentenceDocument.Token token = tokens.get(currentPosition);

        // Set the term based on field type
        String termValue = switch (fieldType) {
            case "word" -> token.word();
            case "lemma" -> token.lemma() != null ? token.lemma().toLowerCase(Locale.ROOT) : null;
            case "tag" -> token.tag();
            case "pos_group" -> token.getPosGroup();
            default -> token.word();
        };

        if (termValue == null || termValue.isEmpty()) {
            // Skip empty tokens
            currentPosition++;
            return incrementToken();
        }

        termAttr.setEmpty().append(termValue);
        posIncrAttr.setPositionIncrement(1);
        offsetAttr.setOffset(token.startOffset(), token.endOffset());

        // Encode payload with lemma and tag for retrieval
        if (fieldType.equals("word")) {
            String payloadString = token.lemma() + "|" + token.tag();
            payloadAttr.setPayload(new BytesRef(payloadString));
        }

        currentPosition++;
        return true;
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        currentPosition = 0;
    }

    @Override
    public void end() throws IOException {
        super.end();
        if (tokens != null && !tokens.isEmpty()) {
            SentenceDocument.Token lastToken = tokens.get(tokens.size() - 1);
            offsetAttr.setOffset(lastToken.endOffset(), lastToken.endOffset());
        }
    }
}

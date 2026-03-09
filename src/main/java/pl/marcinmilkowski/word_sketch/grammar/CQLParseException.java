package pl.marcinmilkowski.word_sketch.grammar;

/**
 * Exception thrown when CQL parsing fails.
 */
public class CQLParseException extends RuntimeException {

    public CQLParseException(String message) {
        super(message);
    }

    public CQLParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

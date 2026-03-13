package pl.marcinmilkowski.word_sketch.exploration.spi;

/**
 * Unchecked exception wrapping infrastructure-level {@link java.io.IOException}s thrown
 * during corpus exploration operations.
 *
 * <p>By wrapping {@code IOException} here, the {@link ExplorationService} interface stays
 * free of checked exceptions, keeping raw I/O concerns contained within the exploration
 * layer and preventing them from leaking into the HTTP API boundary.</p>
 */
public class ExplorationException extends RuntimeException {

    public ExplorationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ExplorationException(String message) {
        super(message);
    }
}

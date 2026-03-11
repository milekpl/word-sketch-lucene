package pl.marcinmilkowski.word_sketch.api;

/**
 * Signals that the HTTP request body exceeds the server's size limit.
 * {@link HttpApiUtils#wrapWithErrorHandling} maps this to an HTTP 413 response.
 */
class RequestEntityTooLargeException extends RuntimeException {

    RequestEntityTooLargeException(String message) {
        super(message);
    }
}

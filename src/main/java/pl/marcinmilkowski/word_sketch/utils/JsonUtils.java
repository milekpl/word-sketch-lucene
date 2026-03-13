package pl.marcinmilkowski.word_sketch.utils;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared Jackson {@link ObjectMapper} instance for the whole application.
 *
 * <p>A single shared instance avoids silent serialisation divergence if Jackson is
 * ever configured (e.g. date formats, custom modules, feature flags). All layers
 * that need JSON parsing or generation should use {@link #mapper()} rather than
 * constructing their own instance.</p>
 *
 * <p>{@link ObjectMapper} is thread-safe for all read and write operations once
 * configured; callers must not mutate the returned instance.</p>
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtils() {}

    /** Returns the application-wide shared {@link ObjectMapper} instance. The instance is thread-safe; callers must not reconfigure it. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}

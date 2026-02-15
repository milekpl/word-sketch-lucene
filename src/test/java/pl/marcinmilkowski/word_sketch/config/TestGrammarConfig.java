package pl.marcinmilkowski.word_sketch.config;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test helper for loading test grammar configurations.
 */
public class TestGrammarConfig {

    private static GrammarConfigLoader testConfig;

    /**
     * Get the test grammar config. Loads on first call.
     */
    public static GrammarConfigLoader getTestConfig() throws IOException {
        if (testConfig == null) {
            Path testGrammarPath = Paths.get("src/test/resources/test-grammar.json");
            testConfig = new GrammarConfigLoader(testGrammarPath);
        }
        return testConfig;
    }

    /**
     * Get test config or throw if not available.
     */
    public static GrammarConfigLoader requireTestConfig() {
        try {
            return getTestConfig();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load test grammar config", e);
        }
    }
}

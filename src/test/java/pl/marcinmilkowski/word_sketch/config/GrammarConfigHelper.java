package pl.marcinmilkowski.word_sketch.config;

import pl.marcinmilkowski.word_sketch.model.PosGroup;
import pl.marcinmilkowski.word_sketch.model.RelationType;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test helper for loading test grammar configurations and building minimal fixture configs.
 */
public class GrammarConfigHelper {

    private static GrammarConfig testConfig;

    /**
     * Get the test grammar config. Loads on first call.
     */
    public static GrammarConfig getTestConfig() throws IOException {
        if (testConfig == null) {
            Path testGrammarPath = Paths.get("src/test/resources/test-grammar.json");
            testConfig = GrammarConfigLoader.load(testGrammarPath);
        }
        return testConfig;
    }

    /**
     * Get test config or throw if not available.
     */
    public static GrammarConfig requireTestConfig() {
        try {
            return getTestConfig();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load test grammar config", e);
        }
    }

    /**
     * Returns a minimal SURFACE-type {@link RelationConfig} for use in tests that need
     * a concrete relation instance without caring about a specific grammar rule.
     *
     * <p>Using this helper instead of an inline {@code new RelationConfig(...)} call
     * insulates tests from positional-parameter changes in the record constructor.</p>
     */
    public static RelationConfig minimalSurfaceRelationConfig() {
        return new RelationConfig(
                "test_surface", "Test Surface", null,
                "[xpos=\"NN.*\"] [xpos=\"JJ.*\"]",
                1, 2, false, 0,
                RelationType.SURFACE,
                PosGroup.ADJ);
    }
}

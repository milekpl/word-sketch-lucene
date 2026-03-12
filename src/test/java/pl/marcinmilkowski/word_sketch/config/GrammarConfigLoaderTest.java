package pl.marcinmilkowski.word_sketch.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.api.GrammarConfigSerializer;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GrammarConfigLoaderTest {

    private static final String MINIMAL_JSON = """
            {
              "version": "1.0-test",
              "relations": [
                {
                  "id": "noun_adj_predicates",
                  "name": "Adjectives (predicative)",
                  "pattern": "1:[xpos=\\"NN.*\\"] [lemma=\\"be\\"] 2:[xpos=\\"JJ.*\\"]"
                },
                {
                  "id": "noun_modifiers",
                  "name": "Modifiers (adjectives)",
                  "pattern": "2:[xpos=\\"JJ.*\\"] 1:[xpos=\\"NN.*\\"]"
                }
              ]
            }
            """;

    @Test
    @DisplayName("fromReader: loads relation names correctly")
    void fromReader_loadsRelationNames() throws IOException {
        GrammarConfig config = GrammarConfigLoader.fromReader(new StringReader(MINIMAL_JSON));

        List<RelationConfig> relations = config.relations();
        assertEquals(2, relations.size());

        assertEquals("noun_adj_predicates", relations.get(0).id());
        assertEquals("Adjectives (predicative)", relations.get(0).name());

        assertEquals("noun_modifiers", relations.get(1).id());
        assertEquals("Modifiers (adjectives)", relations.get(1).name());
    }

    @Test
    @DisplayName("fromReader: getRelation by id returns correct entry")
    void fromReader_getRelationById() throws IOException {
        GrammarConfig config = GrammarConfigLoader.fromReader(new StringReader(MINIMAL_JSON));

        assertTrue(config.relation("noun_adj_predicates").isPresent());
        assertTrue(config.relation("noun_modifiers").isPresent());
        assertFalse(config.relation("nonexistent").isPresent());
    }

    @Test
    @DisplayName("toJson: does not throw when configPath is null (fromReader path)")
    void toJson_doesNotThrowWhenConfigPathIsNull() throws IOException {
        GrammarConfig config = GrammarConfigLoader.fromReader(new StringReader(MINIMAL_JSON));

        assertDoesNotThrow(() -> {
            var json = GrammarConfigSerializer.toJson(config);
            assertNotNull(json);
            assertNotNull(json.get("relations"));
        });
    }

    @Test
    @DisplayName("toJson: config_path is null when loaded via fromReader")
    void toJson_configPathIsNullFromReader() throws IOException {
        GrammarConfig config = GrammarConfigLoader.fromReader(new StringReader(MINIMAL_JSON));

        var json = GrammarConfigSerializer.toJson(config);
        assertNull(json.get("config_path"));
    }
}

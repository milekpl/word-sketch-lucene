package pl.marcinmilkowski.word_sketch.config;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigSerializer;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.model.PosGroup;



import static org.junit.jupiter.api.Assertions.*;

class GrammarConfigSerializerTest {

    @Test
    void toJson_grammarConfig_containsVersionAndRelations() {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        ObjectNode json = GrammarConfigSerializer.toJson(config);

        assertNotNull(json.path("version").textValue(), "version must be present");
        ArrayNode relations = (ArrayNode) json.get("relations");
        assertNotNull(relations, "relations array must be present");
        assertFalse(relations.isEmpty(), "relations must not be empty");
    }

    @Test
    void toJson_grammarConfig_relationsHaveIdAndPattern() {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        ObjectNode json = GrammarConfigSerializer.toJson(config);

        ArrayNode relations = (ArrayNode) json.get("relations");
        ObjectNode first = (ObjectNode) relations.get(0);
        assertNotNull(first.path("id").textValue(), "relation id must be present");
        assertTrue(first.has("head_position"), "head_position must be present");
        assertTrue(first.has("collocate_position"), "collocate_position must be present");
    }

    @Test
    void toJson_relationConfig_includesRelationType_whenPresent() {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        config.relations().stream()
            .filter(rel -> rel.relationType().isPresent())
            .findFirst()
            .ifPresent(rel -> {
                ObjectNode json = GrammarConfigSerializer.toJson(rel);
                assertEquals(rel.relationType().get().name(), json.path("relation_type").asText());
            });
    }

    @Test
    void toJson_relationConfig_omitsNullOptionalFields() {
        RelationConfig minimal = new RelationConfig(
            "test_rel", null, null, null,
            1, 2, false, 0, java.util.Optional.empty(), false, PosGroup.OTHER);
        ObjectNode json = GrammarConfigSerializer.toJson(minimal);

        assertEquals("test_rel", json.path("id").asText());
        assertFalse(json.has("name"), "null name should be omitted");
        assertFalse(json.has("description"), "null description should be omitted");
        assertFalse(json.has("pattern"), "null pattern should be omitted");
        assertFalse(json.has("relation_type"), "absent relationType should be omitted");
    }
}

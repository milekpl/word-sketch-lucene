package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.model.PosGroup;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GrammarConfigSerializerTest {

    @Test
    void toJson_grammarConfig_containsVersionAndRelations() {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        JSONObject json = GrammarConfigSerializer.toJson(config);

        assertNotNull(json.getString("version"), "version must be present");
        JSONArray relations = json.getJSONArray("relations");
        assertNotNull(relations, "relations array must be present");
        assertFalse(relations.isEmpty(), "relations must not be empty");
    }

    @Test
    void toJson_grammarConfig_relationsHaveIdAndPattern() {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        JSONObject json = GrammarConfigSerializer.toJson(config);

        JSONArray relations = json.getJSONArray("relations");
        JSONObject first = relations.getJSONObject(0);
        assertNotNull(first.getString("id"), "relation id must be present");
        assertTrue(first.containsKey("head_position"), "head_position must be present");
        assertTrue(first.containsKey("collocate_position"), "collocate_position must be present");
    }

    @Test
    void toJson_relationConfig_includesRelationType_whenPresent() {
        GrammarConfig config = GrammarConfigHelper.requireTestConfig();
        config.relations().stream()
            .filter(rel -> rel.relationType().isPresent())
            .findFirst()
            .ifPresent(rel -> {
                JSONObject json = GrammarConfigSerializer.toJson(rel);
                assertEquals(rel.relationType().get().name(), json.getString("relation_type"));
            });
    }

    @Test
    void toJson_relationConfig_omitsNullOptionalFields() {
        RelationConfig minimal = new RelationConfig(
            "test_rel", null, null, null,
            1, 2, false, 0, Optional.empty(), false, PosGroup.OTHER);
        JSONObject json = GrammarConfigSerializer.toJson(minimal);

        assertEquals("test_rel", json.getString("id"));
        assertFalse(json.containsKey("name"), "null name should be omitted");
        assertFalse(json.containsKey("description"), "null description should be omitted");
        assertFalse(json.containsKey("pattern"), "null pattern should be omitted");
        assertFalse(json.containsKey("relation_type"), "absent relationType should be omitted");
    }
}

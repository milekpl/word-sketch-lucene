package pl.marcinmilkowski.word_sketch.api;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;

/**
 * Converts {@link GrammarConfig} and {@link RelationConfig} value objects to
 * {@link JSONObject} representations for API responses and diagnostic output.
 *
 * <p>Serialization lives here rather than on the value objects so that
 * {@link GrammarConfig} and {@link RelationConfig} remain pure data carriers
 * with no dependency on the JSON library.</p>
 */
public final class GrammarConfigSerializer {

    private GrammarConfigSerializer() {}

    /**
     * Serializes a {@link GrammarConfig} including its version, config path, and all relations.
     *
     * @param config the grammar config to serialize; must not be null
     * @return a {@link JSONObject} suitable for embedding in an API response
     */
    public static JSONObject toJson(GrammarConfig config) {
        JSONObject root = new JSONObject();
        root.put("version", config.version());
        root.put("config_path", config.configPath() != null ? config.configPath().toString() : null);
        JSONArray relationsArray = new JSONArray();
        for (RelationConfig rel : config.relations()) {
            relationsArray.add(toJson(rel));
        }
        root.put("relations", relationsArray);
        return root;
    }

    /**
     * Serializes a single {@link RelationConfig} to its JSON representation.
     *
     * @param rel the relation config to serialize; must not be null
     * @return a {@link JSONObject} with all non-null fields populated
     */
    public static JSONObject toJson(RelationConfig rel) {
        JSONObject obj = new JSONObject();
        obj.put("id", rel.id());
        if (rel.name() != null) obj.put("name", rel.name());
        if (rel.description() != null) obj.put("description", rel.description());
        if (rel.pattern() != null) obj.put("pattern", rel.pattern());
        obj.put("head_position", rel.headPosition());
        obj.put("collocate_position", rel.collocatePosition());
        obj.put("dual", rel.dual());
        obj.put("default_slop", rel.defaultSlop());
        rel.relationType().ifPresent(rt -> obj.put("relation_type", rt.name()));
        obj.put("exploration_enabled", rel.explorationEnabled());
        return obj;
    }
}

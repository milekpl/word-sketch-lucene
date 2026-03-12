package pl.marcinmilkowski.word_sketch.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Converts {@link GrammarConfig} and {@link RelationConfig} value objects to
 * {@link ObjectNode} representations for API responses and diagnostic output.
 *
 * <p>Serialization lives here rather than on the value objects so that
 * {@link GrammarConfig} and {@link RelationConfig} remain pure data carriers
 * with no dependency on the JSON library.</p>
 */
public final class GrammarConfigSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GrammarConfigSerializer() {}

    /**
     * Serializes a {@link GrammarConfig} including its version, config path, and all relations.
     *
     * @param config the grammar config to serialize; must not be null
     * @return an {@link ObjectNode} suitable for embedding in an API response
     */
    public static ObjectNode toJson(GrammarConfig config) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", config.version());
        if (config.configPath() != null) {
            root.put("config_path", config.configPath().toString());
        }
        ArrayNode relationsArray = MAPPER.createArrayNode();
        for (RelationConfig rel : config.relations()) {
            relationsArray.add(toJson(rel));
        }
        root.set("relations", relationsArray);
        return root;
    }

    /**
     * Serializes a single {@link RelationConfig} to its JSON representation.
     *
     * @param rel the relation config to serialize; must not be null
     * @return an {@link ObjectNode} with all non-null fields populated
     */
    public static ObjectNode toJson(RelationConfig rel) {
        ObjectNode obj = MAPPER.createObjectNode();
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

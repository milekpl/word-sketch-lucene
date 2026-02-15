package pl.marcinmilkowski.word_sketch.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Loads and provides access to grammar configuration from JSON.
 *
 * Expected JSON structure:
 * {
 *   "version": "1.0",
 *   "copulas": ["be", "seem", "become", ...],
 *   "relations": [
 *     {
 *       "id": "noun_adj_predicates",
 *       "name": "...",
 *       "head_pos": "noun",
 *       "collocate_pos": "adj",
 *       "cql_pattern": "[tag=jj.*]",
 *       "uses_copula": true,
 *       "default_slop": 8
 *     },
 *     ...
 *   ]
 * }
 */
public class GrammarConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(GrammarConfigLoader.class);

    private final List<String> copulas;
    private final Set<String> copulaSet;
    private final List<RelationConfig> relations;
    private final Map<String, RelationConfig> relationsById;
    private final String version;
    private final Path configPath;

    /**
     * Load grammar configuration from the specified path.
     *
     * @param configPath Path to the relations.json file
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if the file is invalid
     */
    public GrammarConfigLoader(Path configPath) throws IOException {
        this.configPath = configPath;

        // Load and parse the config
        if (!Files.exists(configPath)) {
            throw new IOException("Grammar config file not found: " + configPath);
        }

        String content = Files.readString(configPath);
        JSONObject root = JSON.parseObject(content);

        // Validate version
        String parsedVersion = root.getString("version");
        if (parsedVersion == null || parsedVersion.isBlank()) {
            throw new IllegalArgumentException("Missing 'version' field in grammar config");
        }
        this.version = parsedVersion;

        // Load copulas
        JSONArray copulasArray = root.getJSONArray("copulas");
        if (copulasArray == null || copulasArray.isEmpty()) {
            throw new IllegalArgumentException("Missing or empty 'copulas' array in grammar config");
        }
        List<String> loadedCopulas = new ArrayList<>();
        Set<String> loadedCopulaSet = new HashSet<>();
        for (int i = 0; i < copulasArray.size(); i++) {
            String copula = copulasArray.getString(i);
            if (copula != null && !copula.isBlank()) {
                String lowerCopula = copula.toLowerCase(Locale.ROOT);
                loadedCopulas.add(lowerCopula);
                loadedCopulaSet.add(lowerCopula);
            }
        }
        this.copulas = Collections.unmodifiableList(loadedCopulas);
        this.copulaSet = Collections.unmodifiableSet(loadedCopulaSet);

        // Load relations
        JSONArray relationsArray = root.getJSONArray("relations");
        if (relationsArray == null || relationsArray.isEmpty()) {
            throw new IllegalArgumentException("Missing or empty 'relations' array in grammar config");
        }
        List<RelationConfig> loadedRelations = new ArrayList<>();
        Map<String, RelationConfig> loadedRelationsById = new HashMap<>();

        for (int i = 0; i < relationsArray.size(); i++) {
            JSONObject relObj = relationsArray.getJSONObject(i);
            if (relObj == null) {
                throw new IllegalArgumentException("Invalid relation at index " + i);
            }

            String id = relObj.getString("id");
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Missing 'id' field for relation at index " + i);
            }

            RelationConfig config = new RelationConfig(
                id,
                relObj.getString("name"),
                relObj.getString("description"),
                relObj.getString("head_pos"),
                relObj.getString("collocate_pos"),
                relObj.getString("cql_pattern"),
                relObj.getBoolean("uses_copula"),
                relObj.getIntValue("default_slop", 10),
                relObj.getString("relation_type"),
                relObj.getBoolean("exploration_enabled")
            );

            if (loadedRelationsById.containsKey(id)) {
                throw new IllegalArgumentException("Duplicate relation id: " + id);
            }

            loadedRelations.add(config);
            loadedRelationsById.put(id, config);
        }
        this.relations = Collections.unmodifiableList(loadedRelations);
        this.relationsById = Collections.unmodifiableMap(loadedRelationsById);

        logger.info("Loaded grammar config version {}: {} copulas, {} relations from {}",
            version, copulas.size(), relations.size(), configPath);
    }

    /**
     * Create a default English grammar config with standard copulas and relations.
     * This is explicit configuration, not a fallback.
     */
    public static GrammarConfigLoader createDefaultEnglish() {
        try {
            return new GrammarConfigLoader(Path.of("grammars/relations.json"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load default grammar config: grammars/relations.json", e);
        }
    }

    /**
     * Get all configured copula lemmas.
     */
    public List<String> getCopulas() {
        return copulas;
    }

    /**
     * Check if a lemma is a copular verb.
     */
    public boolean isCopularVerb(String lemma) {
        if (lemma == null) return false;
        return copulaSet.contains(lemma.toLowerCase(Locale.ROOT));
    }

    /**
     * Get all configured relations.
     */
    public List<RelationConfig> getRelations() {
        return relations;
    }

    /**
     * Get a relation by ID.
     */
    public Optional<RelationConfig> getRelation(String id) {
        return Optional.ofNullable(relationsById.get(id));
    }

    /**
     * Get relations for a specific head POS group.
     */
    public List<RelationConfig> getRelationsForHeadPos(String headPos) {
        return relations.stream()
            .filter(r -> headPos.equalsIgnoreCase(r.headPos()))
            .collect(Collectors.toList());
    }

    /**
     * Find the relation ID for a given relationType (e.g., "ADJ_PREDICATE").
     * Returns the first matching relation's ID, or null if not found.
     */
    public String findRelationIdByType(String relationType) {
        if (relationType == null) return null;
        return relations.stream()
            .filter(r -> relationType.equalsIgnoreCase(r.relationType()))
            .map(RelationConfig::id)
            .findFirst()
            .orElse(null);
    }

    /**
     * Get the configuration version.
     */
    public String getVersion() {
        return version;
    }

    /**
     * Get the config file path.
     */
    public Path getConfigPath() {
        return configPath;
    }

    /**
     * Export the loaded config as a JSONObject for API responses.
     */
    public JSONObject toJson() {
        JSONObject root = new JSONObject();
        root.put("version", version);
        root.put("config_path", configPath.toString());
        root.put("copulas", new JSONArray(copulas));

        JSONArray relationsArray = new JSONArray();
        for (RelationConfig rel : relations) {
            relationsArray.add(rel.toJson());
        }
        root.put("relations", relationsArray);
        return root;
    }

    /**
     * Relation configuration record.
     */
    public record RelationConfig(
        String id,
        String name,
        String description,
        String headPos,
        String collocatePos,
        String cqlPattern,
        Boolean usesCopula,
        int defaultSlop,
        String relationType,
        Boolean explorationEnabled
    ) {
        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            if (name != null) obj.put("name", name);
            if (description != null) obj.put("description", description);
            if (headPos != null) obj.put("head_pos", headPos);
            if (collocatePos != null) obj.put("collocate_pos", collocatePos);
            if (cqlPattern != null) obj.put("cql_pattern", cqlPattern);
            if (usesCopula != null) obj.put("uses_copula", usesCopula);
            obj.put("default_slop", defaultSlop);
            if (relationType != null) obj.put("relation_type", relationType);
            if (explorationEnabled != null) obj.put("exploration_enabled", explorationEnabled);
            return obj;
        }

        /**
         * Derive the collocate POS group from the CQL pattern.
         */
        public String collocatePosGroup() {
            if (cqlPattern == null) return collocatePos != null ? collocatePos : "other";
            String pattern = cqlPattern.toLowerCase(Locale.ROOT);
            if (pattern.contains("tag=in")) return "prep";
            if (pattern.contains("tag=rp") || pattern.contains("tag=to")) return "part";
            if (pattern.contains("tag=jj")) return "adj";
            if (pattern.contains("tag=vb")) return "verb";
            if (pattern.contains("tag=nn") || pattern.contains("tag=pos")) return "noun";
            if (pattern.contains("tag=rb")) return "adv";
            return collocatePos != null ? collocatePos : "other";
        }
    }
}

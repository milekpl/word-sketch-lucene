package pl.marcinmilkowski.word_sketch.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.utils.CqlUtils;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loads and provides access to grammar configuration from JSON.
 *
 * Expected JSON structure:
 * <pre>{@code
 * {
 *   "version": "1.0",
 *   "relations": [
 *     {
 *       "id": "noun_adj_predicates",
 *       "name": "...",
 *       "pattern": "1:[xpos=\"NN.*\"] [lemma=\"be|...\"] 2:[xpos=\"JJ.*\"]",
 *       "default_slop": 8
 *     },
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * <p>The canonical field name for patterns is {@code pattern}.
 * Each relation must have a {@code pattern} field containing a labeled BCQL pattern.
 */
public class GrammarConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(GrammarConfigLoader.class);

    private final List<RelationConfig> relations;
    private final Map<String, RelationConfig> relationsById;
    private final String version;
    private final Path configPath;

    /**
     * Load grammar configuration from the specified path.
     *
     * @param configPath Path to the relations.json file
     * @throws IOException if the file cannot be read or the config is invalid
     */
    public GrammarConfigLoader(Path configPath) throws IOException {
        this(readConfigFile(configPath), configPath);
    }

    /**
     * Create a GrammarConfigLoader from a {@link Reader} — useful for testing without
     * touching the file system.
     *
     * <pre>{@code
     * try (Reader r = new StringReader(jsonContent)) {
     *     GrammarConfigLoader config = GrammarConfigLoader.fromReader(r);
     * }
     * }</pre>
     *
     * @param reader  Reader over a valid grammar JSON document
     * @throws IOException if the reader fails or the JSON is invalid
     */
    public static GrammarConfigLoader fromReader(Reader reader) throws IOException {
        try (reader) {
            char[] buf = new char[65536];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            return new GrammarConfigLoader(sb.toString(), null);
        }
    }

    /** Reads config file content, throwing {@link IOException} if the file does not exist. */
    private static String readConfigFile(Path p) throws IOException {
        if (!Files.exists(p)) throwForMissingConfig(p);
        return Files.readString(p);
    }

    /** Always throws {@link IllegalStateException} for a missing config file. */
    private static void throwForMissingConfig(Path p) {
        throw new IllegalStateException("Grammar config file not found: " + p);
    }

    /** Primary constructor: parses JSON content directly. */
    private GrammarConfigLoader(String content, Path configPath) throws IOException {
        this.configPath = configPath;
        JSONObject root = JSON.parseObject(content);
        this.version = parseAndValidateVersion(root);
        validateNoLegacyKeys(root);
        List<RelationConfig> loadedRelations = new ArrayList<>();
        Map<String, RelationConfig> loadedRelationsById = new HashMap<>();
        parseRelations(root, loadedRelations, loadedRelationsById);
        this.relations = Collections.unmodifiableList(loadedRelations);
        this.relationsById = Collections.unmodifiableMap(loadedRelationsById);
        logger.info("Loaded grammar config version {}: {} relations{}",
            version, relations.size(), configPath != null ? " from " + configPath : "");
    }

    /** Extracts and validates the 'version' field from the root JSON object. */
    private static String parseAndValidateVersion(JSONObject root) throws IOException {
        String parsedVersion = root.getString("version");
        if (parsedVersion == null || parsedVersion.isBlank()) {
            throw new IOException("Config error: Missing 'version' field in grammar config");
        }
        return parsedVersion;
    }

    /** Rejects deprecated top-level keys that must not appear in the config. */
    private static void validateNoLegacyKeys(JSONObject root) throws IOException {
        if (root.containsKey("copulas")) {
            throw new IOException("Config error: Grammar config must NOT contain 'copulas' key. " +
                "Copulas must be embedded in CQL patterns using [lemma=\"be|appear|seem|...\"]. " +
                "See noun_adj_predicates for example.");
        }
    }

    /** Parses all relation entries from the JSON root into the provided mutable lists/maps. */
    private static void parseRelations(JSONObject root,
            List<RelationConfig> loadedRelations,
            Map<String, RelationConfig> loadedRelationsById) throws IOException {
        JSONArray relationsArray = root.getJSONArray("relations");
        if (relationsArray == null || relationsArray.isEmpty()) {
            throw new IOException("Config error: Missing or empty 'relations' array in grammar config");
        }
        for (int i = 0; i < relationsArray.size(); i++) {
            RelationConfig config = parseRelation(relationsArray.getJSONObject(i), i);
            if (loadedRelationsById.containsKey(config.id())) {
                throw new IOException("Config error: Duplicate relation id: " + config.id());
            }
            loadedRelations.add(config);
            loadedRelationsById.put(config.id(), config);
        }
    }

    /** Parses and validates a single relation JSON object into a {@link RelationConfig}. */
    private static RelationConfig parseRelation(JSONObject relObj, int index) throws IOException {
        if (relObj == null) {
            throw new IOException("Config error: Invalid relation at index " + index);
        }

        String id = relObj.getString("id");
        if (id == null || id.isBlank()) {
            throw new IOException("Config error: Missing 'id' field for relation at index " + index);
        }

        // Canonical field name is "pattern"; cql_pattern is no longer supported
        String pattern = relObj.getString("pattern");
        if (pattern == null || pattern.isBlank()) {
            throw new IOException("Config error: Relation '" + id + "' has no 'pattern' field - every relation must have a BCQL pattern");
        }

        int headPosition = relObj.containsKey("head_position")
            ? relObj.getIntValue("head_position") : deriveHeadPositionFromPattern(pattern);
        int collocatePosition = relObj.containsKey("collocate_position")
            ? relObj.getIntValue("collocate_position") : deriveCollocatePositionFromPattern(pattern);

        boolean isDual = relObj.containsKey("dual") && relObj.getBoolean("dual");
        validatePositions(id, pattern, headPosition, collocatePosition, isDual);

        return new RelationConfig(
            id,
            relObj.getString("name"),
            relObj.getString("description"),
            pattern,
            headPosition,
            collocatePosition,
            isDual,
            relObj.getIntValue("default_slop", 10),
            parseRelationType(relObj.getString("relation_type")),
            relObj.getBooleanValue("exploration_enabled", false)
        );
    }

    /**
     * Validates that head/collocate positions are in range for concrete (non-placeholder, non-dual) patterns.
     * Skips validation for patterns containing {@code {head}} or {@code {deprel}} placeholders, or for
     * dual relations where head and collocate refer to the same token.
     */
    private static void validatePositions(String id, String pattern,
            int headPosition, int collocatePosition, boolean isDual) throws IOException {
        boolean hasPlaceholder = pattern.contains("{head}") || pattern.indexOf("{deprel") >= 0;
        if (!hasPlaceholder && !isDual) {
            int tokenCount = countPatternTokens(pattern);
            if (headPosition < 1 || headPosition > tokenCount || collocatePosition < 1 || collocatePosition > tokenCount) {
                throw new IOException("Config error: Relation '" + id + "': positions (" + headPosition + "," + collocatePosition
                    + ") must be between 1 and " + tokenCount + " (pattern has " + tokenCount + " positions: " + pattern + ")");
            }
        }
    }

    /**
     * Create the default grammar config, resolving the path from the
     * {@code grammar.config} system property (default: {@code grammars/relations.json}).
     */
    public static GrammarConfigLoader createDefaultEnglish() {
        String path = System.getProperty("grammar.config", "grammars/relations.json");
        try {
            return new GrammarConfigLoader(Path.of(path));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load default grammar config: " + path, e);
        }
    }

    /**
     * Parse a nullable relation-type string to the {@link RelationType} enum.
     *
     * <p><strong>Nullable contract:</strong> returns {@code null} when the {@code relation_type} field
     * is absent or unrecognised in the grammar JSON. Callers that access
     * {@link RelationConfig#relationType()} must guard against {@code null} before calling
     * {@code .name()} or comparing with enum constants. Converting this field to
     * {@code Optional<RelationType>} was deferred because the value is used in many places
     * across {@code SketchHandlers} and {@code ExplorationHandlers} with both {@code ==}
     * comparisons and {@code .name()} calls that would all require updates simultaneously.
     */
    private static RelationType parseRelationType(String value) {
        if (value == null || value.isBlank()) return null;
        try { return RelationType.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
    }

    /**
     * Each [constraint] is one token.
     * Delegates bracket-walking to {@link CqlUtils#splitCqlTokens}.
     */
    private static int countPatternTokens(String pattern) {
        return CqlUtils.splitCqlTokens(pattern).size();
    }

    /**
     * Derive head position from numbered labels in pattern.
     * Looks for "1:" prefix - that position is the head.
     * Returns default 1 if not found.
     */
    private static int deriveHeadPositionFromPattern(String pattern) {
        return deriveTokenPosition(pattern, '1', 1);
    }

    /**
     * Derive collocate position from numbered labels in pattern.
     * Looks for "2:" prefix - that position is the collocate.
     * Returns default 2 if not found.
     */
    private static int deriveCollocatePositionFromPattern(String pattern) {
        return deriveTokenPosition(pattern, '2', 2);
    }

    /**
     * Shared logic for deriving a token position from a numbered label in a CQL pattern.
     * Scans the pattern counting [token] blocks; returns the count when the target label is found.
     * Extends the bracket-walking in {@link CqlUtils#splitCqlTokens} to also detect
     * numbered label prefixes ({@code 1:[...] 2:[...]}).
     */
    private static int deriveTokenPosition(String pattern, char targetLabel, int defaultPos) {
        if (pattern == null || pattern.isBlank()) {
            return defaultPos;
        }
        int pos = 1;
        int i = 0;
        while (i < pattern.length()) {
            if (Character.isWhitespace(pattern.charAt(i))) {
                i++;
                continue;
            }
            if (i + 1 < pattern.length() && pattern.charAt(i) == targetLabel && pattern.charAt(i + 1) == ':') {
                return pos;
            }
            if (pattern.charAt(i) == '[') {
                // Depth-count to find the matching close bracket, handling nested brackets
                int depth = 0;
                while (i < pattern.length()) {
                    char ch = pattern.charAt(i);
                    if (ch == '"') {
                        i++;
                        while (i < pattern.length() && pattern.charAt(i) != '"') {
                            if (pattern.charAt(i) == '\\') i++;
                            i++;
                        }
                    } else if (ch == '[') {
                        depth++;
                    } else if (ch == ']') {
                        depth--;
                        if (depth == 0) {
                            i++;
                            break;
                        }
                    }
                    i++;
                }
                pos++;
            } else {
                i++;
            }
        }
        return defaultPos;
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
        root.put("config_path", configPath != null ? configPath.toString() : null);
        JSONArray relationsArray = new JSONArray();
        for (RelationConfig rel : relations) {
            relationsArray.add(rel.toJson());
        }
        root.put("relations", relationsArray);
        return root;
    }

}

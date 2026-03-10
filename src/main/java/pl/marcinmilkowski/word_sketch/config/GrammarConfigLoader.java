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
        if (!Files.exists(p)) throwMissing(p);
        return Files.readString(p);
    }

    /** Always throws {@link IllegalStateException} for a missing config file. */
    private static void throwMissing(Path p) {
        throw new IllegalStateException("Grammar config file not found: " + p);
    }

    /** Primary constructor: parses JSON content directly. */
    private GrammarConfigLoader(String content, Path configPath) throws IOException {
        this.configPath = configPath;

        JSONObject root = JSON.parseObject(content);

        // Validate version
        String parsedVersion = root.getString("version");
        if (parsedVersion == null || parsedVersion.isBlank()) {
            throw new IOException("Config error: Missing 'version' field in grammar config");
        }
        this.version = parsedVersion;

        // VALIDATION: Reject 'copulas' key - must be embedded in CQL patterns
        if (root.containsKey("copulas")) {
            throw new IOException("Config error: Grammar config must NOT contain 'copulas' key. " +
                "Copulas must be embedded in CQL patterns using [lemma=\"be|appear|seem|...\"]. " +
                "See noun_adj_predicates for example.");
        }

        // Load relations
        JSONArray relationsArray = root.getJSONArray("relations");
        if (relationsArray == null || relationsArray.isEmpty()) {
            throw new IOException("Config error: Missing or empty 'relations' array in grammar config");
        }
        List<RelationConfig> loadedRelations = new ArrayList<>();
        Map<String, RelationConfig> loadedRelationsById = new HashMap<>();

        for (int i = 0; i < relationsArray.size(); i++) {
            JSONObject relObj = relationsArray.getJSONObject(i);
            if (relObj == null) {
                throw new IOException("Config error: Invalid relation at index " + i);
            }

            String id = relObj.getString("id");
            if (id == null || id.isBlank()) {
                throw new IOException("Config error: Missing 'id' field for relation at index " + i);
            }

            // Canonical field name is "pattern"; cql_pattern is no longer supported
            String pattern = relObj.getString("pattern");
            if (pattern == null || pattern.isBlank()) {
                throw new IOException("Config error: Relation '" + id + "' has no 'pattern' field - every relation must have a BCQL pattern");
            }

            // Parse positions from numbered labels in pattern (1: for head, 2: for collocate)
            // If explicit fields are provided, use those; otherwise derive from pattern
            int derivedHeadPos = deriveHeadPositionFromPattern(pattern);
            int derivedCollocatePos = deriveCollocatePositionFromPattern(pattern);

            int headPosition = relObj.containsKey("head_position") ? relObj.getIntValue("head_position") : derivedHeadPos;
            int collocatePosition = relObj.containsKey("collocate_position") ? relObj.getIntValue("collocate_position") : derivedCollocatePos;

            // VALIDATION: Skip token count validation for:
            // 1. Patterns with {head} or {deprel} placeholders (dependency relations)
            // 2. Dual relations where head and collocate are the same
            boolean hasHead = pattern.contains("{head}");
            boolean hasDeprel = pattern.indexOf("{deprel") >= 0;
            boolean hasPlaceholder = hasHead || hasDeprel;
            boolean isDual = relObj.containsKey("dual") && relObj.getBoolean("dual");

            if (!hasPlaceholder && !isDual) {
                int tokenCount = countPatternTokens(pattern);
                if (headPosition < 1 || headPosition > tokenCount || collocatePosition < 1 || collocatePosition > tokenCount) {
                    throw new IOException("Config error: Relation '" + id + "': positions (" + headPosition + "," + collocatePosition
                        + ") must be between 1 and " + tokenCount + " (pattern has " + tokenCount + " positions: " + pattern + ")");
                }
            }

            boolean dual = isDual;

            RelationConfig config = new RelationConfig(
                id,
                relObj.getString("name"),
                relObj.getString("description"),
                pattern,
                headPosition,
                collocatePosition,
                dual,
                relObj.getIntValue("default_slop", 10),
                parseRelationType(relObj.getString("relation_type")),
                relObj.getBooleanValue("exploration_enabled", false)
            );

            if (loadedRelationsById.containsKey(id)) {
                throw new IOException("Config error: Duplicate relation id: " + id);
            }

            loadedRelations.add(config);
            loadedRelationsById.put(id, config);
        }
        this.relations = Collections.unmodifiableList(loadedRelations);
        this.relationsById = Collections.unmodifiableMap(loadedRelationsById);

        logger.info("Loaded grammar config version {}: {} relations{}",
            version, relations.size(), configPath != null ? " from " + configPath : "");
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

    /** Parse a nullable relation-type string to the {@link RelationType} enum; returns {@code null} if absent. */
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

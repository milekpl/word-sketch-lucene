package pl.marcinmilkowski.word_sketch.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.utils.CqlUtils;
import pl.marcinmilkowski.word_sketch.model.RelationType;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loads grammar configuration from JSON and produces {@link GrammarConfig} value objects.
 *
 * <p>This class is a pure loader: it handles all file-IO and JSON-parsing concerns and
 * returns immutable {@link GrammarConfig} instances. Callers that only need to query the
 * loaded data should accept {@link GrammarConfig} rather than this class.
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
public final class GrammarConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(GrammarConfigLoader.class);

    private GrammarConfigLoader() {}

    /**
     * Load grammar configuration from the specified path.
     *
     * @param configPath Path to the relations.json file
     * @return immutable {@link GrammarConfig} with the parsed relations
     * @throws IOException if the file cannot be read or the config is invalid
     */
    public static GrammarConfig load(Path configPath) throws IOException {
        return parse(readConfigFile(configPath), configPath);
    }

    /**
     * Load grammar configuration from a {@link Reader} — useful for testing without
     * touching the file system.
     *
     * <pre>{@code
     * try (Reader r = new StringReader(jsonContent)) {
     *     GrammarConfig config = GrammarConfigLoader.fromReader(r);
     * }
     * }</pre>
     *
     * @param reader  Reader over a valid grammar JSON document
     * @return immutable {@link GrammarConfig} with the parsed relations
     * @throws IOException if the reader fails or the JSON is invalid
     */
    public static GrammarConfig fromReader(Reader reader) throws IOException {
        var sw = new java.io.StringWriter();
        reader.transferTo(sw);
        return parse(sw.toString(), null);
    }

    private static String readConfigFile(Path p) throws IOException {
        if (!Files.exists(p)) throw missingConfigException(p);
        return Files.readString(p);
    }

    // Always call with `throw`; exists to centralise the exception message.
    private static java.io.FileNotFoundException missingConfigException(Path p) {
        return new java.io.FileNotFoundException("Grammar config file not found: " + p);
    }

    /** Parses JSON content and builds the {@link GrammarConfig} value object. */
    private static GrammarConfig parse(String content, Path configPath) throws IOException {
        JSONObject root = JSON.parseObject(content);
        String version = parseAndValidateVersion(root);
        validateNoLegacyKeys(root);
        List<RelationConfig> loadedRelations = new ArrayList<>();
        Map<String, RelationConfig> loadedRelationsById = new HashMap<>();
        parseRelations(root, loadedRelations, loadedRelationsById);
        logger.info("Loaded grammar config version {}: {} relations{}",
            version, loadedRelations.size(), configPath != null ? " from " + configPath : "");
        return new GrammarConfig(loadedRelations, loadedRelationsById, version, configPath);
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
            relObj.getBooleanValue("exploration_enabled", false),
            RelationPatternBuilder.computeCollocatePosGroup(pattern)
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
     *
     * <p>Wraps any {@link IOException} in an {@link IllegalStateException} with the
     * config path included in the message, so startup failures are immediately actionable.
     * Callers that need to distinguish config-not-found from other IO errors should
     * use {@link #load(Path)} directly and handle {@code IOException}.
     */
    public static GrammarConfig createDefaultEnglish() {
        String path = System.getProperty("grammar.config", "grammars/relations.json");
        Path configPath = Path.of(path);
        try {
            return load(configPath);
        } catch (java.io.FileNotFoundException e) {
            throw new IllegalStateException(
                "Grammar config file not found: '" + path
                    + "'. Set -Dgrammar.config=<path> to override.", e);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Cannot load grammar config from '" + path
                    + "' (malformed JSON or I/O error). Set -Dgrammar.config=<path> to override.", e);
        }
    }

    /**
     * Parse a nullable relation-type string to an {@link Optional} {@link RelationType}.
     *
     * <p>Returns {@code Optional.empty()} when the {@code relation_type} field is absent or
     * unrecognised in the grammar JSON.
     */
    private static Optional<RelationType> parseRelationType(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try { return Optional.of(RelationType.valueOf(value.toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
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
     * Derives the 1-based token position of the labeled position (e.g. {@code 1:[...]} or {@code 2:[...]})
     * in a single pass over the raw pattern string. Counts complete {@code [...]} bracket groups
     * before the first occurrence of {@code targetLabel:} to determine the token index.
     */
    private static int deriveTokenPosition(String pattern, char targetLabel, int defaultPos) {
        if (pattern == null || pattern.isBlank()) return defaultPos;
        int labelIdx = -1;
        for (int i = 0; i + 1 < pattern.length(); i++) {
            if (pattern.charAt(i) == targetLabel && pattern.charAt(i + 1) == ':') {
                labelIdx = i;
                break;
            }
        }
        if (labelIdx < 0) return defaultPos;
        // Count complete [...] tokens before the label position
        int tokenCount = 0;
        int depth = 0;
        for (int i = 0; i < labelIdx; i++) {
            char c = pattern.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) tokenCount++; }
        }
        return tokenCount + 1;
    }

}

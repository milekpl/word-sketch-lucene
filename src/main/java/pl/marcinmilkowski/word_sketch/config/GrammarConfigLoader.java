package pl.marcinmilkowski.word_sketch.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.utils.CqlUtils;
import pl.marcinmilkowski.word_sketch.utils.RelationPatternUtils;
import pl.marcinmilkowski.word_sketch.query.RelationType;

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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GrammarConfigLoader() {}

    /**
     * Reads and parses the grammar JSON at {@code configPath}, validates its structure,
     * and returns an immutable {@link GrammarConfig}.
     *
     * @param configPath Path to the relations.json file
     * @return immutable {@link GrammarConfig} with the parsed relations
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if the config is structurally invalid
     */
    public static GrammarConfig load(Path configPath) throws IOException {
        return parse(readConfigFile(configPath), configPath);
    }

    /**
     * Parses grammar JSON from the given {@link Reader} and returns an immutable
     * {@link GrammarConfig} — useful for testing without touching the file system.
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

    private static GrammarConfig parse(String content, Path configPath) throws IOException {
        ObjectNode root;
        try {
            root = MAPPER.readValue(content, ObjectNode.class);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to parse grammar config" +
                (configPath != null ? " at " + configPath : "") + ": " + e.getMessage(), e);
        }
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
    private static String parseAndValidateVersion(ObjectNode root) {
        JsonNode versionNode = root.path("version");
        String parsedVersion = versionNode.isNull() ? null : versionNode.textValue();
        if (parsedVersion == null || parsedVersion.isBlank()) {
            throw new IllegalArgumentException("Config error: Missing 'version' field in grammar config");
        }
        return parsedVersion;
    }

    /** Rejects deprecated top-level keys that must not appear in the config. */
    private static void validateNoLegacyKeys(ObjectNode root) {
        if (root.has("copulas")) {
            throw new IllegalArgumentException("Config error: Grammar config must NOT contain 'copulas' key. " +
                "Copulas must be embedded in CQL patterns using [lemma=\"be|appear|seem|...\"]. " +
                "See noun_adj_predicates for example.");
        }
    }

    private static void parseRelations(ObjectNode root,
            List<RelationConfig> loadedRelations,
            Map<String, RelationConfig> loadedRelationsById) {
        JsonNode relationsNode = root.path("relations");
        if (!relationsNode.isArray() || relationsNode.isEmpty()) {
            throw new IllegalArgumentException("Config error: Missing or empty 'relations' array in grammar config");
        }
        for (int i = 0; i < relationsNode.size(); i++) {
            JsonNode node = relationsNode.get(i);
            if (!(node instanceof ObjectNode relObj)) {
                logger.warn("Skipping relation at index {}: expected object, got {}", i, node.getNodeType());
                continue;
            }
            RelationConfig config = parseRelation(relObj, i);
            if (loadedRelationsById.containsKey(config.id())) {
                throw new IllegalArgumentException("Config error: Duplicate relation id: " + config.id());
            }
            loadedRelations.add(config);
            loadedRelationsById.put(config.id(), config);
        }
    }

    private static RelationConfig parseRelation(ObjectNode relObj, int index) {
        String id = relObj.path("id").textValue();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Config error: Missing 'id' field for relation at index " + index);
        }

        // Canonical field name is "pattern"; cql_pattern is no longer supported
        String pattern = relObj.path("pattern").textValue();
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("Config error: Relation '" + id + "' has no 'pattern' field - every relation must have a BCQL pattern");
        }

        int headPosition = relObj.has("head_position")
            ? relObj.path("head_position").asInt() : deriveHeadPositionFromPattern(pattern);
        int collocatePosition = relObj.has("collocate_position")
            ? relObj.path("collocate_position").asInt() : deriveCollocatePositionFromPattern(pattern);

        boolean isDual = relObj.has("dual") && relObj.path("dual").asBoolean();
        validatePositions(id, pattern, headPosition, collocatePosition, isDual);

        return new RelationConfig(
            id,
            relObj.path("name").textValue(),
            relObj.path("description").textValue(),
            pattern,
            headPosition,
            collocatePosition,
            isDual,
            relObj.path("default_slop").asInt(10),
            parseRelationType(relObj.path("relation_type").textValue()),
            relObj.path("exploration_enabled").asBoolean(false),
            RelationPatternUtils.computeCollocatePosGroup(pattern)
        );
    }

    /**
     * Validates that head/collocate positions are in range for concrete (non-placeholder, non-dual) patterns.
     * Skips validation for patterns containing {@code {head}} or {@code {deprel}} placeholders, or for
     * dual relations where head and collocate refer to the same token.
     */
    private static void validatePositions(String id, String pattern,
            int headPosition, int collocatePosition, boolean isDual) {
        boolean hasPlaceholder = pattern.contains("{head}") || pattern.indexOf("{deprel") >= 0;
        if (!hasPlaceholder && !isDual) {
            int tokenCount = countPatternTokens(pattern);
            if (headPosition < 1 || headPosition > tokenCount || collocatePosition < 1 || collocatePosition > tokenCount) {
                throw new IllegalArgumentException("Config error: Relation '" + id + "': positions (" + headPosition + "," + collocatePosition
                    + ") must be between 1 and " + tokenCount + " (pattern has " + tokenCount + " positions: " + pattern + ")");
            }
        }
    }

    /**
     * Create the default grammar config, resolving the path from the
     * {@code grammar.config} system property (default: {@code grammars/relations.json}).
     *
     * <p><strong>Two-tier exception contract:</strong> this method intentionally wraps
     * {@link IOException} in {@link IllegalStateException} so that startup code and
     * dependency-injection containers that cannot handle checked exceptions can call it
     * directly. Callers that need to distinguish config-not-found from other I/O errors,
     * or that run in a context where checked exceptions are acceptable (e.g. integration
     * tests, CLI entrypoints), should use {@link #load(Path)} directly and handle
     * {@code IOException}.</p>
     *
     * <p>Wraps any {@link IOException} in an {@link IllegalStateException} with the
     * config path included in the message, so startup failures are immediately actionable.</p>
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
     * Parse a nullable relation-type string to a {@link RelationType}.
     *
     * <p>Returns {@code null} when the {@code relation_type} field is absent (which
     * is valid — not all relations participate in exploration).</p>
     *
     * @throws IllegalArgumentException when the field is present but unrecognised; valid values
     *     are the names of the {@link RelationType} enum (case-insensitive), which typically
     *     indicates a typo in the grammar config.
     */
    private static Optional<RelationType> parseRelationType(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try { return Optional.of(RelationType.valueOf(value.toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Unrecognised relation_type '" + value + "' in grammar config; valid values: "
                + java.util.Arrays.toString(RelationType.values()), e);
        }
    }

    /**
     * Each [constraint] is one token.
     * Delegates bracket-walking to {@link CqlUtils#splitCqlTokens}.
     */
    private static int countPatternTokens(String pattern) {
        return CqlUtils.splitCqlTokens(pattern).size();
    }

    /**
     * Returns the 1-based token index of the {@code 1:[...]} labeled position in the pattern,
     * or 1 when no such label is present.
     */
    private static int deriveHeadPositionFromPattern(String pattern) {
        return deriveTokenPosition(pattern, '1', 1);
    }

    /**
     * Returns the 1-based token index of the {@code 2:[...]} labeled position in the pattern,
     * or 2 when no such label is present.
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

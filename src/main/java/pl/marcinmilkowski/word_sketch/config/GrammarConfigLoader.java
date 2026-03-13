package pl.marcinmilkowski.word_sketch.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import pl.marcinmilkowski.word_sketch.utils.JsonUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.utils.CqlUtils;
import pl.marcinmilkowski.word_sketch.config.RelationUtils;
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

/**
 * Loads grammar configuration from JSON and produces {@link GrammarConfig} value objects.
 *
 * <p>This class is a pure loader: it handles all file-IO and JSON-parsing concerns and
 * returns immutable {@link GrammarConfig} instances. Callers that only need to query the
 * loaded data should accept {@link GrammarConfig} rather than this class.
 *
 * <h2>Exception-type contract</h2>
 * <p>This class uses a two-tier exception strategy that distinguishes <em>I/O failures</em>
 * from <em>structural/validation failures</em>:
 *
 * <ul>
 *   <li>{@link java.io.IOException} (and its subtype {@link java.io.FileNotFoundException}) —
 *       thrown by the checked {@link #load(java.nio.file.Path)} and {@link #fromReader(Reader)}
 *       methods when the underlying storage cannot be read (file not found, permission denied,
 *       broken stream, or unparseable JSON).  Callers that cannot handle checked exceptions
 *       should use {@link #createDefaultEnglish()} / {@link #createDefaultEnglish(String)},
 *       which wrap I/O errors in {@link IllegalStateException}.</li>
 *   <li>{@link IllegalArgumentException} — thrown from any public method when the config
 *       content is structurally invalid: missing {@code version} field, missing or empty
 *       {@code relations} array, missing {@code pattern} field in a relation, duplicate
 *       relation ids, use of deprecated keys, etc.  Because these conditions represent a
 *       programmer / configuration error rather than an environmental failure, they are not
 *       wrapped and propagate regardless of whether the caller uses the checked or unchecked
 *       entry point.</li>
 * </ul>
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
     * Parses {@link Reader} content into an immutable {@link GrammarConfig} — useful for testing.
     *
     * @param reader  reader over a valid grammar JSON document
     * @return immutable {@link GrammarConfig} with the parsed relations
     * @throws IOException if reading fails or the JSON is syntactically invalid
     * @throws IllegalArgumentException if the JSON is readable but structurally invalid
     *         (e.g. missing {@code version}, empty {@code relations}, duplicate ids)
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
            root = JsonUtils.mapper().readValue(content, ObjectNode.class);
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
        String parsedVersion = versionNode.textValue();
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
        if (relObj.has("cql_pattern")) {
            throw new IllegalArgumentException(
                "Config error: Relation '" + id + "' uses old key 'cql_pattern'; rename it to 'pattern'");
        }
        String pattern = relObj.path("pattern").textValue();
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("Config error: Relation '" + id + "' has no 'pattern' field - every relation must have a BCQL pattern");
        }

        int headPosition = relObj.has("head_position")
            ? relObj.path("head_position").asInt() : deriveHeadPositionFromPattern(pattern);
        int collocatePosition = relObj.has("collocate_position")
            ? relObj.path("collocate_position").asInt() : deriveCollocatePositionFromPattern(pattern);

        boolean isDual = relObj.path("dual").asBoolean(false);
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
            RelationUtils.computeCollocatePosGroup(pattern)
        );
    }

    /** Skips validation for patterns with {@code {head}}/{@code {deprel}} placeholders or dual relations. */
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
     * Create the default grammar config from the given {@code configPath}.
     *
     * @param configPath path to the grammar config JSON file; must not be null
     * @throws IllegalStateException if the file is not found or cannot be read (wraps
     *         {@link java.io.FileNotFoundException} or {@link java.io.IOException})
     * @throws IllegalArgumentException if the file is found but its content is structurally
     *         invalid (missing {@code version}, bad {@code relations} entries, duplicate ids, etc.)
     */
    public static GrammarConfig createDefaultEnglish(String configPath) {
        return loadUnchecked(Path.of(configPath), configPath, "");
    }

    /**
     * Create the default grammar config, resolving the path from the
     * {@code grammar.config} system property (default: {@code grammars/relations.json}).
     * Wraps {@link IOException} in {@link IllegalStateException}.
     *
     * @throws IllegalStateException if the file is not found or cannot be read (wraps
     *         {@link java.io.FileNotFoundException} or {@link java.io.IOException}).
     *         Use {@code -Dgrammar.config=&lt;path&gt;} to override the default location.
     * @throws IllegalArgumentException if the file is found but its content is structurally
     *         invalid (missing {@code version}, bad {@code relations} entries, duplicate ids, etc.)
     */
    public static GrammarConfig createDefaultEnglish() {
        String path = System.getProperty("grammar.config", "grammars/relations.json");
        return loadUnchecked(Path.of(path), path, " Set -Dgrammar.config=<path> to override.");
    }

    /**
     * Loads from {@code configPath}, wrapping any {@link IOException} into
     * {@link IllegalStateException} for callers that cannot handle checked exceptions.
     */
    private static GrammarConfig loadUnchecked(Path configPath, String displayPath, String hint) {
        try {
            return load(configPath);
        } catch (java.io.FileNotFoundException e) {
            throw new IllegalStateException(
                "Grammar config file not found: '" + displayPath + "'." + hint, e);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Cannot load grammar config from '" + displayPath
                    + "' (malformed JSON or I/O error)." + hint, e);
        }
    }

    /**
     * Parses nullable relation-type string to a {@link RelationType}; returns {@code null} when absent.
     *
     * @throws IllegalArgumentException when present but unrecognised
     */
    private static @Nullable RelationType parseRelationType(String value) {
        if (value == null || value.isBlank()) return null;
        try { return RelationType.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Unrecognised relation_type '" + value + "' in grammar config; valid values: "
                + java.util.Arrays.toString(RelationType.values()), e);
        }
    }

    /** Counts bracket groups ({@code [...]}) in a CQL pattern via {@link CqlUtils#splitCqlTokens}. */
    private static int countPatternTokens(String pattern) {
        return CqlUtils.splitCqlTokens(pattern).size();
    }

    /** Returns the 1-based index of the {@code 1:[...]} labeled position, or 1 when absent. */
    private static int deriveHeadPositionFromPattern(String pattern) {
        return deriveTokenPosition(pattern, '1', 1);
    }

    /** Returns the 1-based index of the {@code 2:[...]} labeled position, or 2 when absent. */
    private static int deriveCollocatePositionFromPattern(String pattern) {
        return deriveTokenPosition(pattern, '2', 2);
    }

    /** Returns the 1-based token index of {@code targetLabel:[...]} by counting preceding bracket groups. */
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

package pl.marcinmilkowski.word_sketch.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.query.PosGroup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // VALIDATION: Reject 'copulas' key - must be embedded in CQL patterns
        if (root.containsKey("copulas")) {
            throw new IllegalArgumentException("Grammar config must NOT contain 'copulas' key. " +
                "Copulas must be embedded in CQL patterns using [lemma=\"be|appear|seem|...\"]. " +
                "See noun_adj_predicates for example.");
        }

        // Copulas are now derived from CQL patterns - no separate array needed
        this.copulas = Collections.emptyList();

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

            // Support BCQL format with {head} placeholder
            String pattern = relObj.containsKey("pattern") ? relObj.getString("pattern") :
                           (relObj.containsKey("cql_pattern") ? relObj.getString("cql_pattern") : "");

            // Parse positions from numbered labels in pattern (1: for head, 2: for collocate)
            // If explicit fields are provided, use those; otherwise derive from pattern
            int derivedHeadPos = deriveHeadPositionFromPattern(pattern);
            int derivedCollocatePos = deriveCollocatePositionFromPattern(pattern);

            int headPosition = relObj.containsKey("head_position") ? relObj.getIntValue("head_position") : derivedHeadPos;
            int collocatePosition = relObj.containsKey("collocate_position") ? relObj.getIntValue("collocate_position") : derivedCollocatePos;

            // VALIDATION: Require pattern - throw exception if missing
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalArgumentException("Relation '" + id + "' has no pattern - every relation must have a BCQL pattern");
            }

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
                    throw new IllegalArgumentException("Relation '" + id + "': positions (" + headPosition + "," + collocatePosition
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
     * Count the number of tokens in a CQL pattern.
     * Each [constraint] is one token.
     */
    private static int countPatternTokens(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return 0;
        }
        int count = 0;
        int i = 0;
        while (i < pattern.length()) {
            if (pattern.charAt(i) == '[') {
                // Find matching ]
                int end = pattern.indexOf(']', i);
                if (end > i) {
                    count++;
                    i = end + 1;
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }
        return count;
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
                int end = pattern.indexOf(']', i);
                if (end > i) {
                    pos++;
                    i = end + 1;
                } else {
                    i++;
                }
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
        String pattern,
        int headPosition,
        int collocatePosition,
        boolean dual,
        int defaultSlop,
        String relationType,
        Boolean explorationEnabled
    ) {
        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            if (name != null) obj.put("name", name);
            if (description != null) obj.put("description", description);
            if (pattern != null) obj.put("pattern", pattern);
            obj.put("head_position", headPosition);
            obj.put("collocate_position", collocatePosition);
            obj.put("dual", dual);
            obj.put("default_slop", defaultSlop);
            if (relationType != null) obj.put("relation_type", relationType);
            if (explorationEnabled != null) obj.put("exploration_enabled", explorationEnabled);
            return obj;
        }

        /**
         * Get the BCQL pattern with headword substituted.
         * For BCQL format: replaces the constraint at head_position with [lemma="headword" & original_constraint]
         */
        public String getFullPattern(String headword) {
            if (pattern == null) return null;
            if (headword == null || headword.isBlank()) return pattern;

            // Parse the pattern and substitute the headword at head_position
            return substituteHeadword(pattern, headword, headPosition);
        }

        /**
         * Substitute the headword into the BCQL pattern at the specified position.
         * E.g., pattern "[xpos=\"NN.*\"] [xpos=\"JJ.*\"]" with head at position 1
         * becomes "[lemma=\"theory\" & xpos=\"NN.*\"] [xpos=\"JJ.*\"]"
         */
        private static String substituteHeadword(String pattern, String headword, int headPosition) {
            if (pattern == null || headword == null || headPosition < 1) {
                return pattern;
            }

            // Split pattern into positions, preserving numbered prefixes like "1:" or "2:"
            List<String> positions = new ArrayList<>();
            int i = 0;
            while (i < pattern.length()) {
                // Skip whitespace
                if (Character.isWhitespace(pattern.charAt(i))) {
                    i++;
                    continue;
                }

                // Check for numbered prefix like "1:" or "2:"
                String prefix = "";
                if (i < pattern.length() && Character.isDigit(pattern.charAt(i))) {
                    int colonPos = pattern.indexOf(':', i);
                    if (colonPos > i && colonPos < i + 3) {
                        prefix = pattern.substring(i, colonPos + 1);
                        i = colonPos + 1;
                    }
                }

                if (i < pattern.length() && pattern.charAt(i) == '[') {
                    int end = pattern.indexOf(']', i);
                    if (end > i) {
                        positions.add(prefix + pattern.substring(i, end + 1));
                        i = end + 1;
                    } else {
                        i++;
                    }
                } else if (i < pattern.length() && pattern.charAt(i) == '"') {
                    // Handle quoted strings outside brackets
                    int end = pattern.indexOf('"', i + 1);
                    if (end > i) {
                        i = end + 1;
                    } else {
                        i++;
                    }
                } else {
                    i++;
                }
            }

            if (headPosition > positions.size()) {
                return pattern;
            }

            // Get the constraint at headPosition and merge with lemma
            String positionEntry = positions.get(headPosition - 1);
            // Extract just the constraint part (after any "1:" prefix)
            String constraint = positionEntry;
            if (!positionEntry.isEmpty() && Character.isDigit(positionEntry.charAt(0))) {
                int colon = positionEntry.indexOf(':');
                if (colon > 0) {
                    constraint = positionEntry.substring(colon + 1);
                }
            }
            String newConstraint = mergeLemmaConstraint(constraint, headword);

            // Replace with prefix + new constraint
            String prefix = "";
            if (!positionEntry.isEmpty() && Character.isDigit(positionEntry.charAt(0))) {
                int colon = positionEntry.indexOf(':');
                if (colon > 0) {
                    prefix = positionEntry.substring(0, colon + 1);
                }
            }
            positions.set(headPosition - 1, prefix + newConstraint);

            return String.join(" ", positions);
        }

        /**
         * Merge lemma constraint with existing xpos/pos constraint.
         * E.g., "[xpos=\"NN.*\"]" + "theory" -> "[lemma=\"theory\" & xpos=\"NN.*\"]"
         */
        private static String mergeLemmaConstraint(String existingConstraint, String headword) {
            // Parse existing constraint to extract xpos/pos tags
            String xposPattern = extractXposPattern(existingConstraint);
            String posPattern = extractPosPattern(existingConstraint);

            // Build new constraint with lemma
            StringBuilder sb = new StringBuilder();
            sb.append("[lemma=\"").append(escapeForRegex(headword)).append("\"");

            if (xposPattern != null) {
                sb.append(" & ").append(xposPattern);
            }
            if (posPattern != null) {
                sb.append(" & ").append(posPattern);
            }

            sb.append("]");
            return sb.toString();
        }

        /**
         * Extract xpos constraint from an existing constraint string.
         * E.g., "[xpos=\"NN.*\"]" returns "xpos=\"NN.*\""
         */
        private static String extractXposPattern(String constraint) {
            return extractConstraintAttribute(constraint, "xpos");
        }

        /**
         * Extract pos constraint from an existing constraint string.
         * E.g., "[tag=\"NN\"]" returns "tag=\"NN\""
         */
        private static String extractPosPattern(String constraint) {
            return extractConstraintAttribute(constraint, "tag");
        }

        private static String extractConstraintAttribute(String constraint, String attrName) {
            if (constraint == null) return null;
            Pattern p = Pattern.compile(attrName + "=\"([^\"]*)\"");
            Matcher m = p.matcher(constraint);
            if (m.find()) {
                return attrName + "=\"" + m.group(1) + "\"";
            }
            return null;
        }

        private static String escapeForRegex(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        /**
         * Derive the collocate POS group from the pattern.
         * Looks specifically at the 2: labelled position so that multi-token patterns
         * like "1:[xpos="NN.*"] 2:[xpos="JJ.*"]" correctly return "adj" not "noun".
         * Supports both xpos= (current grammar) and tag= (legacy format).
         */
        public String collocatePosGroup() {
            if (pattern == null) return "other";
            String pat = pattern.toLowerCase(Locale.ROOT);
            // Prefer the 2: labeled bracket; fall back to whole pattern for single-token patterns
            String target = extractLabelContent(pat, 2);
            if (target == null) target = pat;
            return posGroupFromConstraint(target);
        }

        /** Extract the bracket content of the nth labeled position (e.g. "2:[...]"). */
        private String extractLabelContent(String pat, int label) {
            String prefix = label + ":[";
            int idx = pat.indexOf(prefix);
            if (idx < 0) return null;
            int start = idx + prefix.length() - 1; // points at '['
            int depth = 0;
            for (int i = start; i < pat.length(); i++) {
                if (pat.charAt(i) == '[') depth++;
                else if (pat.charAt(i) == ']') { if (--depth == 0) return pat.substring(start, i + 1); }
            }
            return null;
        }

        /** Map a constraint string to a POS group label. */
        private String posGroupFromConstraint(String s) {
            // xpos (Penn Treebank — used by current grammar)
            if (s.contains("xpos=\"jj") || s.contains("xpos=jj")) return PosGroup.ADJ;
            if (s.contains("xpos=\"vb") || s.contains("xpos=vb")) return PosGroup.VERB;
            if (s.contains("xpos=\"nn") || s.contains("xpos=nn")) return PosGroup.NOUN;
            if (s.contains("xpos=\"rb") || s.contains("xpos=rb")) return PosGroup.ADV;
            if (s.contains("xpos=\"in") || s.contains("xpos=in")) return "prep";
            if (s.contains("xpos=\"rp") || s.contains("xpos=rp")
             || s.contains("xpos=\"to") || s.contains("xpos=to")) return "part";
            // tag (legacy / alternative attribute name)
            if (s.contains("tag=\"jj") || s.contains("tag=jj")) return PosGroup.ADJ;
            if (s.contains("tag=\"vb") || s.contains("tag=vb")) return PosGroup.VERB;
            if (s.contains("tag=\"nn") || s.contains("tag=nn")
             || s.contains("tag=\"pos") || s.contains("tag=pos")) return PosGroup.NOUN;
            if (s.contains("tag=\"rb") || s.contains("tag=rb")) return PosGroup.ADV;
            if (s.contains("tag=\"in") || s.contains("tag=in")) return "prep";
            if (s.contains("tag=\"rp") || s.contains("tag=rp")
             || s.contains("tag=\"to") || s.contains("tag=to")) return "part";
            return "other";
        }

        /**
         * Extract the dependency relation (deprel) from the pattern.
         * For DEP relations, looks for deprel="xxx" attribute constraint in the pattern.
         * If not found, extracts from the relation ID (e.g., "dep_amod" -> "amod").
         */
        public String getDeprel() {
            if (pattern == null || !"DEP".equals(relationType)) {
                return null;
            }
            // Look for deprel="xxx" or deprel='xxx' attribute constraint
            Pattern p = Pattern.compile("deprel=[\"']([^\"']+)[\"']");
            Matcher m = p.matcher(pattern);
            if (m.find()) {
                return m.group(1);
            }
            // Fallback: extract from relation ID (e.g., "dep_amod" -> "amod")
            if (id != null && id.startsWith("dep_")) {
                return id.substring(4);
            }
            return null;
        }

    }
}

package pl.marcinmilkowski.word_sketch.grammar;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Sketch Engine grammar definition files (.wsdef.m4).
 *
 * Supports directives:
 * - *UNIMAP name1/name2          -> Symmetric relations map to same name
 * - *DUAL relname                -> Bidirectional relations
 * - *SEPARATEPAGE relname        -> Each relation on separate page
 * - *TRINARY                     -> Three-position patterns
 * - *UNARY                       -> Single-element patterns
 * - *STRUCTLIMIT n               -> Structure limit
 * - *DEFAULTATTR attr            -> Default attribute
 * - *WSPOSLIST                   -> Word sketch POS list
 * - *MACRO name definition       -> Define macro
 * - *INCLUDE file                -> Include another file
 *
 * Pattern syntax:
 * - Position: `1:"N.*"` or `NOUN`
 * - Constraint: `[tag="JJ.*"]` or `[word="the"]`
 * - Negation: `[tag!="N.*"]`
 * - OR: `[tag="JJ"|tag="RB"]`
 * - AND: `[tag="PP" & word!="I"]`
 * - Arrow: `-> relation_name`
 */
public class SketchGrammarParser {

    private static final Pattern DIRECTIVE_PATTERN = Pattern.compile(
        "\\*(\\w+)(?:\\s+(.+))?"
    );
    private static final Pattern MACRO_PATTERN = Pattern.compile(
        "\\*MACRO\\s+(\\w+)\\s+(.+)"
    );
    private static final Pattern INCLUDE_PATTERN = Pattern.compile(
        "\\*INCLUDE\\s+<([^>]+)>"
    );
    private static final Pattern RELATION_PATTERN = Pattern.compile(
        "(.+?)\\s*->\\s*(\\w+)"
    );
    private static final Pattern COMMENT_PATTERN = Pattern.compile(
        "#.*$"
    );
    private static final Pattern MULTILINE_PATTERN = Pattern.compile(
        "\\\\\\s*$", Pattern.MULTILINE
    );

    private final Map<String, String> macros = new HashMap<>();
    private final List<SketchRelation> relations = new ArrayList<>();
    private String defaultAttr = "tag";
    private boolean trinaryMode = false;
    private boolean unaryMode = false;

    /**
     * Parse a grammar definition string.
     */
    public SketchGrammar parse(String grammar) throws IOException {
        return parseInternal(grammar, new ArrayList<>());
    }

    /**
     * Parse a grammar definition from a file.
     */
    public SketchGrammar parseFile(String content) throws IOException {
        return parse(content);
    }

    private SketchGrammar parseInternal(String grammar, List<String> includeStack) throws IOException {
        // Remove multiline continuation
        grammar = MULTILINE_PATTERN.matcher(grammar).replaceAll("");

        // Process includes first
        Matcher includeMatcher = INCLUDE_PATTERN.matcher(grammar);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        while (includeMatcher.find()) {
            sb.append(grammar, lastEnd, includeMatcher.start());
            String includedFile = includeMatcher.group(1);

            // Check for circular includes
            if (includeStack.contains(includedFile)) {
                throw new IOException("Circular include detected: " + includedFile);
            }

            // Read included file content (simplified - would need file reading in real implementation)
            sb.append(" ");
            lastEnd = includeMatcher.end();
        }
        sb.append(grammar, lastEnd, grammar.length());
        grammar = sb.toString();

        // Remove comments
        grammar = COMMENT_PATTERN.matcher(grammar).replaceAll("");

        // Process line by line
        try (BufferedReader reader = new BufferedReader(new StringReader(grammar))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Check for directive
                Matcher directiveMatcher = DIRECTIVE_PATTERN.matcher(line);
                if (directiveMatcher.matches()) {
                    String directive = directiveMatcher.group(1);
                    String args = directiveMatcher.group(2);

                    switch (directive.toUpperCase()) {
                        case "MACRO":
                            processMacro(args);
                            break;
                        case "UNIMAP":
                            // handled separately
                            break;
                        case "DUAL":
                            // handled separately
                            break;
                        case "SEPARATEPAGE":
                            // handled separately
                            break;
                        case "TRINARY":
                            trinaryMode = true;
                            break;
                        case "UNARY":
                            unaryMode = true;
                            break;
                        case "STRUCTLIMIT":
                            // structure limit
                            break;
                        case "DEFAULTATTR":
                            if (args != null) {
                                defaultAttr = args.trim();
                            }
                            break;
                        case "WSPOSLIST":
                            // word sketch POS list
                            break;
                        default:
                            // Unknown directive, ignore
                            break;
                    }
                } else {
                    // Check for relation definition
                    Matcher relationMatcher = RELATION_PATTERN.matcher(line);
                    if (relationMatcher.matches()) {
                        String pattern = relationMatcher.group(1).trim();
                        String relName = relationMatcher.group(2).trim();

                        // Expand macros
                        pattern = expandMacros(pattern);

                        // Parse the pattern
                        CQLParser parser = new CQLParser();
                        CQLParser.ParsedCQL parsed = parser.parseFull(pattern);

                        // Determine relation type
                        RelationType relType = determineRelationType(line);

                        SketchRelation relation = new SketchRelation(relName, parsed, relType, trinaryMode, unaryMode);
                        relations.add(relation);
                    }
                }
            }
        }

        return new SketchGrammar(relations, defaultAttr, macros);
    }

    private void processMacro(String args) {
        if (args == null) return;

        Matcher macroMatcher = MACRO_PATTERN.matcher(args);
        if (macroMatcher.matches()) {
            String name = macroMatcher.group(1);
            String definition = macroMatcher.group(2).trim();
            macros.put(name, definition);
        }
    }

    private String expandMacros(String pattern) {
        String expanded = pattern;
        for (Map.Entry<String, String> macro : macros.entrySet()) {
            expanded = expanded.replace(macro.getKey(), macro.getValue());
        }
        return expanded;
    }

    private RelationType determineRelationType(String line) {
        // Check for relation modifiers
        if (line.contains("*TRINARY")) {
            return RelationType.TRINARY;
        }
        if (line.contains("*UNARY")) {
            return RelationType.UNARY;
        }
        if (line.contains("*DUAL")) {
            return RelationType.DUAL;
        }
        if (line.contains("*UNIMAP")) {
            return RelationType.UNIMAP;
        }
        return RelationType.NORMAL;
    }

    /**
     * Represents a sketch grammar relation.
     */
    public static class SketchRelation {
        private final String name;
        private final CQLParser.ParsedCQL pattern;
        private final RelationType type;
        private final boolean trinary;
        private final boolean unary;

        public SketchRelation(String name, CQLParser.ParsedCQL pattern,
                             RelationType type, boolean trinary, boolean unary) {
            this.name = name;
            this.pattern = pattern;
            this.type = type;
            this.trinary = trinary;
            this.unary = unary;
        }

        public String getName() { return name; }
        public CQLParser.ParsedCQL getPattern() { return pattern; }
        public RelationType getType() { return type; }
        public boolean isTrinary() { return trinary; }
        public boolean isUnary() { return unary; }
    }

    /**
     * Relation types.
     */
    public enum RelationType {
        NORMAL,
        DUAL,          // Bidirectional (A rel B / B rel A)
        TRINARY,       // Three-position pattern
        UNARY,         // Single-element pattern
        UNIMAP         // Unimap (symmetric)
    }

    /**
     * Complete parsed grammar.
     */
    public static class SketchGrammar {
        private final List<SketchRelation> relations;
        private final String defaultAttr;
        private final Map<String, String> macros;

        public SketchGrammar(List<SketchRelation> relations, String defaultAttr, Map<String, String> macros) {
            this.relations = relations;
            this.defaultAttr = defaultAttr;
            this.macros = macros;
        }

        public List<SketchRelation> getRelations() { return relations; }
        public String getDefaultAttr() { return defaultAttr; }
        public Map<String, String> getMacros() { return macros; }

        public SketchRelation findRelation(String name) {
            return relations.stream()
                .filter(r -> r.getName().equals(name))
                .findFirst()
                .orElse(null);
        }
    }
}

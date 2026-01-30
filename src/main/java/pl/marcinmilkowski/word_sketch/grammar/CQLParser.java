package pl.marcinmilkowski.word_sketch.grammar;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for CQL (Corpus Query Language) patterns.
 *
 * CQL Syntax:
 * - Labeled position: `1:"N.*"` or `1:NOUN` (macro expansion)
 * - Constraint: `[tag="JJ.*"]` or `[word="the"]`
 * - Negation: `[tag!="N.*"]`
 * - OR: `[tag="JJ"|tag="RB"]`
 * - AND: `[tag="PP" & word!="I" & word!="he"]`
 * - Distance: `{0,3}` (0 to 3 words between elements)
 * - Repetition: `"V.*"{0,2}` (0 to 2 repetitions)
 * - Agreement: `& 1.tag = 2.tag`
 * - Lemma substitution: `%(3.lemma)`
 * - Multi-alternative: Pattern1 --- Pattern2
 */
public class CQLParser {

    private static final Pattern LABELED_POSITION = Pattern.compile(
        "(\\d+):(?:\"([^\"]+)\"|(\\w+))"
    );
    private static final Pattern UNLABELED_POSITION = Pattern.compile(
        "(?:\"([^\"]+)\"|([a-zA-Z.*]+))"
    );
    private static final Pattern CONSTRAINT = Pattern.compile(
        "\\[([^\\]]*)\\]"
    );
    // Match distance: ~{min,max} or ~ {min,max} (supports negative values for "before" position)
    private static final Pattern DISTANCE = Pattern.compile(
        "~\\s*\\{(-?\\d+)(?:,(-?\\d+))?\\}"
    );
    private static final Pattern REPITITION = Pattern.compile(
        "\"(?:[^\"]+)\"\\{(\\d+)(?:,(\\d+))?\\}"
    );
    private static final Pattern AGREEMENT_RULE = Pattern.compile(
        "&\\s*(\\d+)\\.(\\w+)\\s*(=|!=)\\s*(\\d+)\\.(\\w+)"
    );
    private static final Pattern LEMMA_SUBSTITUTION = Pattern.compile(
        "%\\((\\d+)\\.(\\w+)\\)"
    );
    private static final Pattern PATTERN_ALTERNATIVE = Pattern.compile(
        "\\n?\\s*---\\s*\\n?"
    );

    /**
     * Find the matching closing bracket for an opening bracket at the given position.
     * Handles brackets inside quoted strings.
     */
    private static int findMatchingBracket(String s, int openPos) {
        if (openPos >= s.length() || s.charAt(openPos) != '[') {
            return -1;
        }
        boolean inQuote = false;
        for (int i = openPos + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ']' && !inQuote) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Parse a CQL pattern and return a CQLPattern object.
     */
    public CQLPattern parse(String cql) throws CQLParseException {
        ParsedCQL parsed = parseInternal(cql);
        return buildCQLPattern(parsed);
    }

    /**
     * Parse a CQL pattern and return full parsed data including alternatives and agreement rules.
     */
    public ParsedCQL parseFull(String cql) throws CQLParseException {
        return parseInternal(cql);
    }

    private ParsedCQL parseInternal(String cql) throws CQLParseException {
        ParsedCQL parsed = new ParsedCQL();

        // Check for pattern alternatives (--- separator)
        String[] altParts = PATTERN_ALTERNATIVE.split(cql);
        if (altParts.length > 1) {
            for (String alt : altParts) {
                if (!alt.trim().isEmpty()) {
                    parsed.addAlternative(alt.trim());
                }
            }
            // Parse first alternative as main pattern
            cql = altParts[0];
        }

        String remaining = cql.trim();

        // Extract agreement rules
        Matcher agreementMatcher = AGREEMENT_RULE.matcher(remaining);
        while (agreementMatcher.find()) {
            int firstPos = Integer.parseInt(agreementMatcher.group(1));
            String firstField = agreementMatcher.group(2);
            String op = agreementMatcher.group(3);
            int secondPos = Integer.parseInt(agreementMatcher.group(4));
            String secondField = agreementMatcher.group(5);
            parsed.addAgreementRule(new AgreementRule(firstPos, firstField, secondPos, secondField, op));
        }
        // Remove agreement rules from pattern
        remaining = remaining.replaceAll("&\\s*\\d+\\.\\w+\\s*(=|!=)\\s*\\d+\\.\\w+", "").trim();

        int pos = 0;
        while (pos < remaining.length()) {
            // Skip whitespace
            while (pos < remaining.length() && Character.isWhitespace(remaining.charAt(pos))) {
                pos++;
            }
            if (pos >= remaining.length()) break;

            // Try to parse a constraint first (e.g., [tag="JJ"])
            if (remaining.charAt(pos) == '[') {
                int closePos = findMatchingBracket(remaining, pos);
                if (closePos == -1) {
                    throw new CQLParseException("Unclosed constraint at: " + remaining.substring(pos));
                }
                String constraintStr = remaining.substring(pos + 1, closePos);
                CQLPattern.Constraint constraint = parseConstraint(constraintStr);

                // Check for distance modifier after constraint
                int afterPos = closePos + 1;
                int minDistance = 0;
                int maxDistance = 0;
                String afterText = remaining.substring(afterPos);

                // Count leading whitespace to adjust position
                int leadingSpaces = 0;
                while (leadingSpaces < afterText.length() && Character.isWhitespace(afterText.charAt(leadingSpaces))) {
                    leadingSpaces++;
                }
                afterText = afterText.substring(leadingSpaces).trim();

                Matcher distanceMatcher = DISTANCE.matcher(afterText);
                if (distanceMatcher.lookingAt()) {
                    minDistance = Integer.parseInt(distanceMatcher.group(1));
                    maxDistance = distanceMatcher.group(2) != null ? Integer.parseInt(distanceMatcher.group(2)) : minDistance;
                    // Position after distance = original afterPos + leading spaces + distance end
                    afterPos = afterPos + leadingSpaces + distanceMatcher.end();
                } else {
                    // No distance modifier, position is after the constraint and any whitespace
                    afterPos = afterPos + leadingSpaces;
                }

                CQLPattern.PatternElement element = new CQLPattern.PatternElement(-1, "", constraint, 1, 1, minDistance, maxDistance, null);
                parsed.addElement(element);
                pos = afterPos;
                continue;
            }

            // Try to parse a labeled position (e.g., 1:"N.*")
            Matcher labeledMatcher = LABELED_POSITION.matcher(remaining);
            if (labeledMatcher.find(pos) && labeledMatcher.start() == pos) {
                int position = Integer.parseInt(labeledMatcher.group(1));
                String target = labeledMatcher.group(2) != null ? labeledMatcher.group(2) : labeledMatcher.group(3);
                String after = remaining.substring(labeledMatcher.end()).trim();
                CQLPattern.PatternElement element = buildElement(position, target, after);
                parsed.addElement(element);
                pos += labeledMatcher.end();
                continue;
            }

            // Try to parse an unlabeled position (e.g., "N.*" or NOUN)
            Matcher unlabeledMatcher = UNLABELED_POSITION.matcher(remaining);
            if (unlabeledMatcher.find(pos) && unlabeledMatcher.start() == pos) {
                String target = unlabeledMatcher.group(1) != null ? unlabeledMatcher.group(1) : unlabeledMatcher.group(2);
                String after = remaining.substring(unlabeledMatcher.end()).trim();
                CQLPattern.PatternElement element = buildElement(-1, target, after);
                parsed.addElement(element);
                pos += unlabeledMatcher.end();
                continue;
            }

            throw new CQLParseException("Cannot parse pattern at: " + remaining.substring(pos));
        }

        return parsed;
    }

    /**
     * Split a string by | character, but ignore | inside quoted strings.
     * This distinguishes between:
     *   - Field-level OR: tag="JJ"|tag="RB" -> ["tag=\"JJ\"", "tag=\"RB\""]
     *   - Value-level regex: word="be|remain|seem" -> ["word=\"be|remain|seem\""]
     * 
     * @param str The constraint string to split
     * @return List of parts split by | outside quotes
     */
    private List<String> splitByOrOutsideQuotes(String str) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            
            if (c == '"') {
                inQuote = !inQuote;
                current.append(c);
            } else if (c == '|' && !inQuote) {
                // Split here - this is an OR operator between constraints
                parts.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        
        // Add the last part
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        
        return parts;
    }

    private CQLPattern.PatternElement buildElement(int position, String target, String after) throws CQLParseException {
        CQLPattern.Constraint constraint = null;
        int minRepetition = 1;
        int maxRepetition = 1;
        int minDistance = 0;
        int maxDistance = 0;

        // Check for constraint
        Matcher constraintMatcher = CONSTRAINT.matcher(after);
        if (constraintMatcher.lookingAt()) {
            String constraintStr = constraintMatcher.group(1);
            constraint = parseConstraint(constraintStr);
            after = after.substring(constraintMatcher.end()).trim();
        }

        // Check for distance
        Matcher distanceMatcher = DISTANCE.matcher(after);
        if (distanceMatcher.lookingAt()) {
            minDistance = Integer.parseInt(distanceMatcher.group(1));
            maxDistance = distanceMatcher.group(2) != null ? Integer.parseInt(distanceMatcher.group(2)) : minDistance;
            after = after.substring(distanceMatcher.end()).trim();
        }

        // Check for repetition
        Matcher repetitionMatcher = REPITITION.matcher(after);
        if (repetitionMatcher.lookingAt()) {
            minRepetition = Integer.parseInt(repetitionMatcher.group(1));
            maxRepetition = repetitionMatcher.group(2) != null ? Integer.parseInt(repetitionMatcher.group(2)) : minRepetition;
        }

        // Check for lemma substitution
        Matcher lemmaMatcher = LEMMA_SUBSTITUTION.matcher(target);
        if (lemmaMatcher.find()) {
            int subPos = Integer.parseInt(lemmaMatcher.group(1));
            String subField = lemmaMatcher.group(2);
            target = lemmaMatcher.replaceFirst("");
            // Store lemma substitution info - will be used during compilation
        }

        return new CQLPattern.PatternElement(position, target, constraint,
            minRepetition, maxRepetition, minDistance, maxDistance, null);
    }

    private CQLPattern.Constraint parseConstraint(String constraintStr) throws CQLParseException {
        boolean negated = false;
        String field;
        String pattern;

        // Handle prefix negation: [!tag="pattern"] -> tag="pattern", negated=true
        String trimmed = constraintStr.trim();
        if (trimmed.startsWith("!")) {
            negated = true;
            constraintStr = trimmed.substring(1).trim();
        }

        // Check for AND operator (&) inside the constraint
        if (constraintStr.contains("&")) {
            return parseAndConstraint(constraintStr, negated);
        }

        // Split by OR operator (but respect quoted strings)
        List<String> partsList = splitByOrOutsideQuotes(constraintStr);
        String[] parts = partsList.toArray(new String[0]);

        if (parts.length == 1) {
            // Use regex split with limit to avoid breaking on = in pattern
            String[] fieldPattern = parts[0].trim().split("\\s*=\\s*", 2);
            if (fieldPattern.length != 2) {
                throw new CQLParseException("Invalid constraint format: " + parts[0]);
            }
            field = fieldPattern[0].trim();
            // Handle negation: field!="pattern" -> field="pattern", negated=true
            if (field.endsWith("!")) {
                negated = true;
                field = field.substring(0, field.length() - 1);
            }
            pattern = fieldPattern[1].trim().replace("\"", "");
        } else {
            List<CQLPattern.Constraint> orConstraints = new ArrayList<>();
            for (String part : parts) {
                String[] fieldPattern = part.trim().split("\\s*=\\s*", 2);
                if (fieldPattern.length != 2) {
                    throw new CQLParseException("Invalid constraint format: " + part);
                }
                String fld = fieldPattern[0].trim();
                boolean partNegated = false;
                if (fld.endsWith("!")) {
                    partNegated = true;
                    fld = fld.substring(0, fld.length() - 1);
                }
                orConstraints.add(new CQLPattern.Constraint(
                    fld,
                    fieldPattern[1].trim().replace("\"", ""),
                    partNegated
                ));
            }
            String[] baseFieldPattern = parts[0].trim().split("\\s*=\\s*", 2);
            field = baseFieldPattern[0].trim();
            // Handle negation in base pattern
            if (field.endsWith("!")) {
                negated = true;
                field = field.substring(0, field.length() - 1);
            }
            pattern = baseFieldPattern[1].trim().replace("\"", "");
            return new CQLPattern.Constraint(field, pattern, negated, orConstraints);
        }

        return new CQLPattern.Constraint(field, pattern, negated);
    }

    /**
     * Parse an AND constraint like: tag="PP" & word!="I" & word!="he"
     */
    private CQLPattern.Constraint parseAndConstraint(String constraintStr, boolean outerNegated) throws CQLParseException {
        // Split by &, but not & inside quoted strings
        List<String> andParts = splitByAnd(constraintStr);

        List<CQLPattern.Constraint> andConstraints = new ArrayList<>();
        boolean allNegated = true;
        for (String part : andParts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            // Parse each AND part as a constraint
            String[] fieldPattern = part.split("=", 2);
            if (fieldPattern.length != 2) {
                throw new CQLParseException("Invalid constraint format: " + part);
            }

            String field = fieldPattern[0].trim();
            String pattern = fieldPattern[1].trim().replace("\"", "");
            // Handle both infix negation (tag!="X") and check pattern for ! prefix
            boolean negated = field.endsWith("!") || pattern.startsWith("!");
            if (field.endsWith("!")) {
                field = field.substring(0, field.length() - 1);
            }
            if (pattern.startsWith("!")) {
                pattern = pattern.substring(1);
            }

            andConstraints.add(new CQLPattern.Constraint(field, pattern, negated));
            if (!negated) {
                allNegated = false;
            }
        }

        if (andConstraints.isEmpty()) {
            throw new CQLParseException("AND constraint has no parts: " + constraintStr);
        }

        // If all parts are negated, mark outer constraint as negated
        boolean isNegated = outerNegated || allNegated;

        if (andConstraints.size() == 1) {
            return new CQLPattern.Constraint(
                andConstraints.get(0).getField(),
                andConstraints.get(0).getPattern(),
                isNegated
            );
        }

        // Return a special constraint that represents AND of multiple
        String mainField = andConstraints.get(0).getField();
        String mainPattern = andConstraints.get(0).getPattern();
        return new CQLPattern.Constraint(mainField, mainPattern, isNegated,
                                          new ArrayList<>(), andConstraints);
    }

    /**
     * Split by & while respecting quoted strings.
     */
    private List<String> splitByAnd(String s) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        boolean inQuote = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (!inQuote && c == '&') {
                parts.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        parts.add(s.substring(start).trim());
        return parts;
    }

    private CQLPattern buildCQLPattern(ParsedCQL parsed) {
        CQLPattern pattern = new CQLPattern();
        for (CQLPattern.PatternElement element : parsed.getElements()) {
            pattern.addElement(element);
        }
        return pattern;
    }

    public static String expandMacros(String cql, java.util.Map<String, String> macros) {
        String expanded = cql;
        for (java.util.Map.Entry<String, String> macro : macros.entrySet()) {
            expanded = expanded.replace(macro.getKey(), macro.getValue());
        }
        return expanded;
    }

    // Inner class to hold extended parsed data
    public static class ParsedCQL {
        private final List<CQLPattern.PatternElement> elements = new ArrayList<>();
        private final List<CQLPattern.AgreementRule> agreementRules = new ArrayList<>();
        private final List<String> alternatives = new ArrayList<>();

        public void addElement(CQLPattern.PatternElement element) { elements.add(element); }
        public void addAgreementRule(CQLPattern.AgreementRule rule) { agreementRules.add(rule); }
        public void addAlternative(String alt) { alternatives.add(alt); }

        public List<CQLPattern.PatternElement> getElements() { return elements; }
        public List<CQLPattern.AgreementRule> getAgreementRules() { return agreementRules; }
        public List<String> getAlternatives() { return alternatives; }
        public boolean hasAlternatives() { return !alternatives.isEmpty(); }
    }

    // Re-export AgreementRule for convenience
    public static class AgreementRule extends CQLPattern.AgreementRule {
        public AgreementRule(int firstPosition, String firstField,
                            int secondPosition, String secondField, String operator) {
            super(firstPosition, firstField, secondPosition, secondField, operator);
        }
    }

    // Re-export PatternElement for convenience
    public static class PatternElement extends CQLPattern.PatternElement {
        public PatternElement(int position, String target) {
            super(position, target);
        }

        public PatternElement(int position, String target, CQLPattern.Constraint constraint,
                              int minRepetition, int maxRepetition,
                              int minDistance, int maxDistance, String label) {
            super(position, target, constraint, minRepetition, maxRepetition, minDistance, maxDistance, label);
        }
    }
}

package pl.marcinmilkowski.word_sketch.query;

import java.util.regex.Pattern;

/**
 * Base class for token predicates that match a field value against a wildcard/regex pattern.
 */
abstract class AbstractPatternPredicate implements TokenPredicate {

    private final String pattern;

    protected AbstractPatternPredicate(String pattern) {
        this.pattern = pattern;
    }

    protected String getPattern() {
        return pattern;
    }

    /** Extract the field value to match against the pattern from the given token. */
    protected abstract String extractValue(Token token);

    /**
     * Whether to lowercase the pattern before matching (default: true).
     * Override to return false to preserve pattern case (e.g. TagPredicate).
     */
    protected boolean isPatternCaseInsensitive() {
        return true;
    }

    @Override
    public boolean test(Token token) {
        if (pattern == null || pattern.isEmpty() || pattern.equals("*")) {
            return true;
        }
        String value = extractValue(token);
        String effectivePattern = isPatternCaseInsensitive() ? pattern.toLowerCase() : pattern;
        String regex = wildcardToRegex(effectivePattern);
        return Pattern.matches(regex, value.toLowerCase());
    }

    private String wildcardToRegex(String pat) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pat.length(); i++) {
            char c = pat.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if (c == '?') {
                sb.append(".");
            } else if (".^$|()[]{}\\+".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

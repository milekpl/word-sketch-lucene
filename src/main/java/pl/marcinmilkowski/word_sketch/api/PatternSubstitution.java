package pl.marcinmilkowski.word_sketch.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Static helpers for substituting collocates into CQL patterns.
 */
public class PatternSubstitution {

    private static final Logger logger = LoggerFactory.getLogger(PatternSubstitution.class);

    private PatternSubstitution() {}

    /**
     * Substitute the collocate word into a BCQL pattern at the specified position.
     */
    public static String substituteCollocate(String pattern, String collocate, int collocatePosition) {
        if (pattern == null || collocate == null || collocatePosition < 1) {
            return pattern;
        }

        // Split pattern into CQL positions
        List<String> patternPositions = new ArrayList<>();
        int i = 0;
        while (i < pattern.length()) {
            if (pattern.charAt(i) == '[') {
                int end = pattern.indexOf(']', i);
                if (end > i) {
                    patternPositions.add(pattern.substring(i, end + 1));
                    i = end + 1;
                } else {
                    i++;
                }
            } else if (pattern.charAt(i) == '"') {
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

        logger.debug("substituteCollocate: patternPositions.size()={}, collocatePosition={}", patternPositions.size(), collocatePosition);
        logger.debug("Pattern positions: {}", patternPositions);

        if (collocatePosition > patternPositions.size()) {
            logger.debug("Returning early - position > size");
            return pattern;
        }

        // Replace the constraint at collocatePosition with lemma constraint for the collocate
        String originalConstraint = patternPositions.get(collocatePosition - 1);
        logger.debug("originalConstraint at position {}: {}", collocatePosition, originalConstraint);
        // Extract xpos/tag from original and merge with lemma
        String xposPattern = extractXposFromConstraint(originalConstraint);
        logger.debug("xposPattern: {}", xposPattern);
        StringBuilder newConstraint = new StringBuilder();
        newConstraint.append("[lemma=\"").append(escapeForRegex(collocate)).append("\"");
        if (xposPattern != null) {
            newConstraint.append(" & ").append(xposPattern);
        }
        newConstraint.append("]");
        logger.debug("newConstraint: {}", newConstraint);

        patternPositions.set(collocatePosition - 1, newConstraint.toString());
        String result = String.join(" ", patternPositions);
        logger.debug("final result: {}", result);
        return result;
    }

    public static String extractXposFromConstraint(String constraint) {
        if (constraint == null) return null;
        // Look for xpos="..." pattern
        int xposStart = constraint.indexOf("xpos=\"");
        if (xposStart >= 0) {
            int start = xposStart;
            int end = constraint.indexOf("\"", xposStart + 6);
            if (end > xposStart) {
                return constraint.substring(start, end + 1);
            }
        }
        // Also check for tag="..."
        int tagStart = constraint.indexOf("tag=\"");
        if (tagStart >= 0) {
            int start = tagStart;
            int end = constraint.indexOf("\"", tagStart + 5);
            if (end > tagStart) {
                return constraint.substring(start, end + 1);
            }
        }
        return null;
    }

    public static String escapeForRegex(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

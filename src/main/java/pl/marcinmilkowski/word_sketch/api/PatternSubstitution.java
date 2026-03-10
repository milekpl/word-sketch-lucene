package pl.marcinmilkowski.word_sketch.api;

import java.util.List;
import pl.marcinmilkowski.word_sketch.utils.CqlUtils;

/**
 * Static helpers for substituting collocates into CQL patterns.
 */
public class PatternSubstitution {

    private PatternSubstitution() {}

    /**
     * Substitute the collocate word into a BCQL pattern at the specified position.
     */
    public static String substituteCollocate(String pattern, String collocate, int collocatePosition) {
        if (pattern == null || collocate == null || collocatePosition < 1) {
            return pattern;
        }

        // Split pattern into CQL token positions using shared CqlUtils bracket-walker
        List<String> patternPositions = CqlUtils.splitCqlTokens(pattern);

        if (collocatePosition > patternPositions.size()) {
            return pattern;
        }

        // Replace the constraint at collocatePosition with lemma constraint for the collocate
        String originalConstraint = patternPositions.get(collocatePosition - 1);
        // Extract xpos/tag from original and merge with lemma
        String xposPattern = extractXposFromConstraint(originalConstraint);
        StringBuilder newConstraint = new StringBuilder();
        newConstraint.append("[lemma=\"").append(CqlUtils.escapeForRegex(collocate)).append("\"");
        if (xposPattern != null) {
            newConstraint.append(" & ").append(xposPattern);
        }
        newConstraint.append("]");

        patternPositions.set(collocatePosition - 1, newConstraint.toString());
        return String.join(" ", patternPositions);
    }

    public static String extractXposFromConstraint(String constraint) {
        if (constraint == null) return null;
        // Look for xpos="..." pattern
        int xposStart = constraint.indexOf("xpos=\"");
        if (xposStart >= 0) {
            int end = constraint.indexOf("\"", xposStart + 6);
            if (end > xposStart) {
                return constraint.substring(xposStart, end + 1);
            }
        }
        // Also check for tag="..."
        int tagStart = constraint.indexOf("tag=\"");
        if (tagStart >= 0) {
            int end = constraint.indexOf("\"", tagStart + 5);
            if (end > tagStart) {
                return constraint.substring(tagStart, end + 1);
            }
        }
        return null;
    }
}

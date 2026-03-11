package pl.marcinmilkowski.word_sketch.utils;

import java.util.List;

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
        String xposPattern = CqlUtils.extractConstraintAttribute(originalConstraint, "xpos");
        if (xposPattern == null) {
            xposPattern = CqlUtils.extractConstraintAttribute(originalConstraint, "tag");
        }
        StringBuilder newConstraint = new StringBuilder();
        newConstraint.append("[lemma=\"").append(CqlUtils.escapeForRegex(collocate)).append("\"");
        if (xposPattern != null) {
            newConstraint.append(" & ").append(xposPattern);
        }
        newConstraint.append("]");

        patternPositions.set(collocatePosition - 1, newConstraint.toString());
        return String.join(" ", patternPositions);
    }


}

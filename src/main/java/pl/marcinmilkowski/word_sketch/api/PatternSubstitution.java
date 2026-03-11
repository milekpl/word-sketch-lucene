package pl.marcinmilkowski.word_sketch.api;

import pl.marcinmilkowski.word_sketch.utils.CqlUtils;

/**
 * Static helpers for substituting collocates into CQL patterns.
 */
class PatternSubstitution {

    private PatternSubstitution() {}

    /**
     * Substitute the collocate word into a BCQL pattern at the specified position.
     */
    static String substituteCollocate(String pattern, String collocate, int collocatePosition) {
        return CqlUtils.substituteAtPosition(pattern, collocate, collocatePosition);
    }
}

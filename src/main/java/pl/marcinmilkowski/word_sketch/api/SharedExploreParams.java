package pl.marcinmilkowski.word_sketch.api;

/**
 * Parameters shared across all exploration handlers: maximum collocates, minimum
 * shared-by count, and minimum logDice threshold.
 */
record SharedExploreParams(int topCollocates, int minShared, double logDiceThreshold) {}

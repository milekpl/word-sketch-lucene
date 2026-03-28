package pl.marcinmilkowski.word_sketch.model.sketch;

import java.util.List;

/**
 * Result of a paginated BCQL concordance query.
 *
 * @param total    total number of matching hits in the index (all pages combined)
 * @param offset   0-based index of the first result in this page
 * @param pageSize the number of results requested for this page
 * @param results  the concordance results for this page (length ≤ pageSize)
 */
public record BcqlPage(long total, int offset, int pageSize, List<CollocateResult> results) {}

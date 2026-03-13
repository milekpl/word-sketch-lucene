package pl.marcinmilkowski.word_sketch.api.model;

/**
 * Typed response record for a single collocate in a word sketch relation.
 *
 * <p>Replaces the raw {@code Map<String,Object>} previously returned by
 * {@code SketchResponseAssembler.formatWordSketchResult}. Jackson serialises records
 * directly via their component accessors.</p>
 */
public record CollocateEntry(
        String lemma,
        long frequency,
        double logDice,
        String pos) {}

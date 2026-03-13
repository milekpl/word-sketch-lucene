package pl.marcinmilkowski.word_sketch.api.model;

/** A single concordance line, carrying only the fields relevant to examples display. */
public record ExampleEntry(String sentence, String raw) {}

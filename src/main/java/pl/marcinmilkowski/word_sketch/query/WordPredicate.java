package pl.marcinmilkowski.word_sketch.query;

/** Predicate that matches tokens based on their word form. */
public class WordPredicate extends AbstractPatternPredicate {
    public WordPredicate(String pattern) { super(pattern); }

    @Override
    protected String extractValue(Token token) { return token.getWord(); }

    @Override
    public String toString() { return "Word(" + getPattern() + ")"; }
}

package pl.marcinmilkowski.word_sketch.query;

/** Predicate that matches tokens based on their POS tag. */
public class TagPredicate extends AbstractPatternPredicate {
    public TagPredicate(String pattern) { super(pattern); }

    @Override
    protected String extractValue(Token token) { return token.getTag(); }

    @Override
    protected boolean isPatternCaseInsensitive() { return false; }

    @Override
    public String toString() { return "Tag(" + getPattern() + ")"; }
}

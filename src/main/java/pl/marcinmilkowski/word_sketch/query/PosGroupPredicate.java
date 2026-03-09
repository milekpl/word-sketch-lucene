package pl.marcinmilkowski.word_sketch.query;

/** Predicate that matches tokens based on their POS group. */
public class PosGroupPredicate extends AbstractPatternPredicate {
    public PosGroupPredicate(String pattern) { super(pattern); }

    @Override
    protected String extractValue(Token token) { return token.getPosGroup(); }

    @Override
    public String toString() { return "PosGroup(" + getPattern() + ")"; }
}

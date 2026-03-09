package pl.marcinmilkowski.word_sketch.query;

/** Predicate that matches tokens based on their lemma. */
public class LemmaPredicate extends AbstractPatternPredicate {
    public LemmaPredicate(String pattern) { super(pattern); }

    @Override
    protected String extractValue(Token token) { return token.getLemma(); }

    @Override
    public String toString() { return "Lemma(" + getPattern() + ")"; }
}

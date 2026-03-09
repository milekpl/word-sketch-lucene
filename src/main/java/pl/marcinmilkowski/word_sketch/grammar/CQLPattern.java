package pl.marcinmilkowski.word_sketch.grammar;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a CQL (Corpus Query Language) pattern element.
 */
public class CQLPattern {
    private final List<PatternElement> elements = new ArrayList<>();

    public List<PatternElement> getElements() {
        return elements;
    }

    public void addElement(PatternElement element) {
        elements.add(element);
    }

    /**
     * A single element in a CQL pattern.
     */
    public static class PatternElement {
        private final int position;  // Labeled position (e.g., 1, 2) or -1 for unlabeled
        private final String target; // The pattern to match (e.g., "N.*", "JJ.*")
        private final Constraint constraint; // Optional constraint
        private final int minRepetition; // Minimum repetitions (default 1)
        private final int maxRepetition; // Maximum repetitions (default 1)
        private final int minDistance; // Minimum distance for previous element
        private final int maxDistance; // Maximum distance for next element
        private final String label; // Optional label for the element

        public PatternElement(int position, String target) {
            this(position, target, null, 1, 1, 0, Integer.MAX_VALUE, null);
        }

        public PatternElement(int position, String target, Constraint constraint,
                              int minRepetition, int maxRepetition,
                              int minDistance, int maxDistance, String label) {
            this.position = position;
            this.target = target;
            this.constraint = constraint;
            this.minRepetition = minRepetition;
            this.maxRepetition = maxRepetition;
            this.minDistance = minDistance;
            this.maxDistance = maxDistance;
            this.label = label;
        }

        public int getPosition() { return position; }
        public String getTarget() { return target; }
        public Constraint getConstraint() { return constraint; }
        public int getMinRepetition() { return minRepetition; }
        public int getMaxRepetition() { return maxRepetition; }
        public int getMinDistance() { return minDistance; }
        public int getMaxDistance() { return maxDistance; }
        public String getLabel() { return label; }

        public boolean hasLabel() { return label != null; }
        public boolean isLabeled() { return position > 0; }
    }

    /**
     * Represents a constraint on a pattern element.
     */
    public static class Constraint {
        private final String field;  // field to match against (tag, word, lemma)
        private final String pattern; // Regex pattern
        private final boolean negated; // Whether this is a negation
        private final List<Constraint> orConstraints; // For OR patterns (tag="A"|tag="B")
        private final List<Constraint> andConstraints; // For AND constraints (tag="A" & word="B")

        public Constraint(String field, String pattern, boolean negated) {
            this(field, pattern, negated, new ArrayList<>(), new ArrayList<>());
        }

        public Constraint(String field, String pattern, boolean negated, List<Constraint> orConstraints) {
            this(field, pattern, negated, orConstraints, new ArrayList<>());
        }

        public Constraint(String field, String pattern, boolean negated,
                         List<Constraint> orConstraints, List<Constraint> andConstraints) {
            this.field = field;
            this.pattern = pattern;
            this.negated = negated;
            this.orConstraints = orConstraints;
            this.andConstraints = andConstraints;
        }

        public String getField() { return field; }
        public String getPattern() { return pattern; }
        public boolean isNegated() { return negated; }
        public List<Constraint> getOrConstraints() { return orConstraints; }
        public List<Constraint> getAndConstraints() { return andConstraints; }
        public boolean isOr() { return !orConstraints.isEmpty(); }
        public boolean isAnd() { return !andConstraints.isEmpty(); }

        /**
         * Create an AND constraint combining multiple conditions.
         */
        public static Constraint and(Constraint... constraints) {
            if (constraints.length == 0) {
                throw new IllegalArgumentException("AND requires at least one constraint");
            }
            if (constraints.length == 1) {
                return constraints[0];
            }
            // Return a constraint that represents the AND of all
            Constraint first = constraints[0];
            return new Constraint(first.getField(), first.getPattern(), first.isNegated(),
                                  new ArrayList<>(), java.util.Arrays.asList(constraints));
        }
    }

    /**
     * Represents an agreement rule (e.g., 1.tag = 2.tag).
     */
    public static class AgreementRule {
        private final int firstPosition;
        private final String firstField;
        private final int secondPosition;
        private final String secondField;
        private final String operator; // "=" or "!="

        public AgreementRule(int firstPosition, String firstField,
                            int secondPosition, String secondField, String operator) {
            this.firstPosition = firstPosition;
            this.firstField = firstField;
            this.secondPosition = secondPosition;
            this.secondField = secondField;
            this.operator = operator;
        }

        public int getFirstPosition() { return firstPosition; }
        public String getFirstField() { return firstField; }
        public int getSecondPosition() { return secondPosition; }
        public String getSecondField() { return secondField; }
        public String getOperator() { return operator; }
    }
}

package pl.marcinmilkowski.word_sketch.query;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.queries.spans.SpanContainingQuery;
import org.apache.lucene.queries.spans.SpanFirstQuery;
import org.apache.lucene.queries.spans.SpanMultiTermQueryWrapper;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanNotQuery;
import org.apache.lucene.queries.spans.SpanOrQuery;
import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanTermQuery;
import pl.marcinmilkowski.word_sketch.grammar.CQLPattern;
import pl.marcinmilkowski.word_sketch.grammar.CQLParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiler that translates CQL patterns to Lucene SpanQueries.
 *
 * Supports:
 * - Basic labeled/unlabeled positions
 * - Constraints with AND, OR, negation
 * - Distance and repetition modifiers
 * - Agreement rules
 * - Lemma substitution
 * - Multi-alternative patterns
 */
public class CQLToLuceneCompiler {

    private static final String FIELD_TAG = "tag";
    private static final String FIELD_WORD = "word";
    private static final String FIELD_LEMMA = "lemma";
    private static final String FIELD_POS_GROUP = "pos_group";

    /**
     * Compile a CQL pattern to a Lucene SpanQuery.
     * For multi-field patterns, returns a BooleanQuery combining the conditions.
     * Note: True multi-field span matching requires post-filtering in application code.
     */
    public SpanQuery compile(CQLPattern pattern) {
        List<CQLPattern.PatternElement> elements = pattern.getElements();

        if (elements.isEmpty()) {
            throw new IllegalArgumentException("CQL pattern has no elements");
        }

        if (elements.size() == 1) {
            return buildQuery(elements.get(0));
        }

        // Build SpanQueries for each element
        List<SpanQuery> spanQueries = new ArrayList<>();
        for (CQLPattern.PatternElement element : elements) {
            spanQueries.add(buildQuery(element));
        }

        // Group queries by field - SpanNearQuery requires same field
        Map<String, List<SpanQuery>> queriesByField = new LinkedHashMap<>();
        for (SpanQuery q : spanQueries) {
            String field = getQueryField(q);
            queriesByField.computeIfAbsent(field, k -> new ArrayList<>()).add(q);
        }

        // If all queries are on the same field, use SpanNearQuery
        if (queriesByField.size() == 1) {
            int slop = calculateTotalSlop(elements);
            SpanQuery[] clauses = spanQueries.toArray(new SpanQuery[0]);
            return new SpanNearQuery(clauses, slop, true);
        }

        // For different fields, we can't use SpanNearQuery (requires same field)
        // Return queries from the first field - the WordSketchQueryExecutor
        // will handle multi-field matching via post-filtering in matchesConstraints()
        String firstField = queriesByField.keySet().iterator().next();
        List<SpanQuery> firstFieldQueries = queriesByField.get(firstField);
        
        if (firstFieldQueries.size() == 1) {
            return firstFieldQueries.get(0);
        }
        
        int slop = calculateTotalSlop(elements);
        return new SpanNearQuery(firstFieldQueries.toArray(new SpanQuery[0]), slop, true);
    }

    /**
     * Get the field name from a SpanQuery.
     */
    private String getQueryField(SpanQuery query) {
        if (query instanceof SpanTermQuery) {
            return ((SpanTermQuery) query).getTerm().field();
        } else if (query instanceof SpanMultiTermQueryWrapper) {
            var mtqw = (SpanMultiTermQueryWrapper<?>) query;
            if (mtqw.getWrappedQuery() instanceof WildcardQuery) {
                return ((WildcardQuery) mtqw.getWrappedQuery()).getTerm().field();
            }
        } else if (query instanceof SpanContainingQuery) {
            // Recursively check inner query
            return getQueryField(((SpanContainingQuery) query).getBig());
        } else if (query instanceof SpanNearQuery) {
            // For SpanNearQuery, return the field of the first clause
            SpanQuery[] clauses = ((SpanNearQuery) query).getClauses();
            if (clauses.length > 0) {
                return getQueryField(clauses[0]);
            }
        }
        return FIELD_LEMMA; // Default
    }

    /**
     * Compile a full parsed CQL including agreement rules and alternatives.
     */
    public CompilationResult compileFull(CQLParser.ParsedCQL parsed) {
        List<SpanQuery> queries = new ArrayList<>();
        List<String> alternatives = parsed.getAlternatives();
        List<CQLPattern.AgreementRule> agreements = parsed.getAgreementRules();

        // Main pattern
        if (!parsed.getElements().isEmpty()) {
            CQLPattern mainPattern = new CQLPattern();
            for (CQLPattern.PatternElement element : parsed.getElements()) {
                mainPattern.addElement(element);
            }
            queries.add(compile(mainPattern));
        }

        // Alternative patterns
        for (String alt : alternatives) {
            try {
                CQLPattern altPattern = new CQLParser().parse(alt);
                queries.add(compile(altPattern));
            } catch (Exception e) {
                // Skip invalid alternatives
            }
        }

        SpanQuery mainQuery;
        if (queries.size() == 1) {
            mainQuery = queries.get(0);
        } else {
            mainQuery = new SpanOrQuery(queries.toArray(new SpanQuery[0]));
        }

        return new CompilationResult(mainQuery, agreements);
    }

    private SpanQuery buildQuery(CQLPattern.PatternElement element) {
        String target = element.getTarget();
        String field = determineField(target);
        CQLPattern.Constraint constraint = element.getConstraint();

        SpanQuery query;

        // If target is empty, use constraint as the primary query
        if (target.isEmpty() && constraint != null) {
            query = buildConstraintQuery(constraint);
            if (element.isLabeled()) {
                query = new SpanFirstQuery(query, element.getPosition());
            }
        } else if (target.isEmpty() && constraint == null) {
            // Match all tokens - use wildcard query on lemma field
            MultiTermQuery mtq = new WildcardQuery(new Term(FIELD_LEMMA, "*"));
            query = new SpanMultiTermQueryWrapper<>(mtq);
        } else if (constraint != null && isGenericTarget(target)) {
            // Target is generic (like .*), use constraint as the primary query
            // This handles cross-field constraints like target=.* with lemma=problem
            query = buildConstraintQuery(constraint);
            if (element.isLabeled()) {
                query = new SpanFirstQuery(query, element.getPosition());
            }
        } else {
            if (element.isLabeled()) {
                SpanQuery baseQuery = buildTermQuery(target, field);
                query = new SpanFirstQuery(baseQuery, element.getPosition());
            } else {
                query = buildTermQuery(target, field);
            }

            if (constraint != null) {
                query = applyConstraint(query, constraint);
            }
        }

        // Handle repetition
        if (element.getMinRepetition() > 1 || element.getMaxRepetition() > 1) {
            query = buildRepetitionQuery(query, element.getMinRepetition(), element.getMaxRepetition());
        }

        return query;
    }

    /**
     * Check if a target pattern is generic (matches everything).
     */
    private boolean isGenericTarget(String target) {
        return target.equals(".*") || target.equals("*") || target.isEmpty();
    }

    /**
     * Build a query that matches multiple repetitions of the same pattern.
     */
    private SpanQuery buildRepetitionQuery(SpanQuery base, int min, int max) {
        if (min == 1 && max == 1) {
            return base;
        }
        // For simplicity, use SpanNear with itself
        SpanQuery[] clauses = new SpanQuery[max];
        for (int i = 0; i < max; i++) {
            clauses[i] = base;
        }
        return new SpanNearQuery(clauses, 0, true);
    }

    private SpanQuery buildTermQuery(String target, String field) {
        target = target.replace("\"", "").trim();
        if (target.isEmpty()) {
            // Match all tokens - use wildcard query on lemma field
            MultiTermQuery mtq = new WildcardQuery(new Term(FIELD_LEMMA, "*"));
            return new SpanMultiTermQueryWrapper<>(mtq);
        }

        // For tag field, convert to lowercase since index stores tags lowercase
        if (FIELD_TAG.equals(field)) {
            target = target.toLowerCase();
        }

        // Handle regex OR patterns (a|b|c) by creating a SpanOrQuery
        if (target.contains("|") && !target.contains(".*")) {
            String[] alternatives = target.split("\\|");
            List<SpanQuery> orQueries = new ArrayList<>();
            for (String alt : alternatives) {
                alt = alt.trim();
                if (!alt.isEmpty()) {
                    // Recursively build query for each alternative
                    orQueries.add(buildTermQuery(alt, field));
                }
            }
            if (orQueries.size() == 1) {
                return orQueries.get(0);
            }
            return new SpanOrQuery(orQueries.toArray(new SpanQuery[0]));
        }

        // Check if it contains regex metacharacters
        if (containsRegexMetacharacters(target)) {
            String wildcard = convertRegexToWildcard(target);
            MultiTermQuery mtq = new WildcardQuery(new Term(field, wildcard));
            return new SpanMultiTermQueryWrapper<>(mtq);
        }

        // Handle prefix patterns like N.*
        if (target.endsWith(".*")) {
            String prefix = target.substring(0, target.length() - 2);
            MultiTermQuery mtq = new WildcardQuery(new Term(field, prefix + "*"));
            return new SpanMultiTermQueryWrapper<>(mtq);
        }

        // Exact match
        return new SpanTermQuery(new Term(field, target));
    }

    /**
     * Convert a simple regex pattern to Lucene wildcard syntax.
     */
    private String convertRegexToWildcard(String regex) {
        // Convert to lowercase first since tags are stored lowercase
        regex = regex.toLowerCase();
        if (regex.startsWith("n.*") || regex.startsWith("v.*") ||
            regex.startsWith("j.*") || regex.startsWith("r.*") ||
            regex.startsWith("d.*") || regex.startsWith("p.*") ||
            regex.startsWith("i.*") || regex.startsWith("c.*") ||
            regex.startsWith("u.*") || regex.startsWith("m.*")) {
            String prefix = regex.substring(0, 1);
            return prefix + "*";
        }
        return regex.replace(".*", "*").replace("?", "?");
    }

    private String determineField(String target) {
        target = target.replace("\"", "").trim();

        // Tags are uppercase patterns or patterns with regex metacharacters (like vb.*, jj.*)
        if (target.matches("^[A-Z].*") ||
            target.matches("^\".*\"") ||
            target.contains(".*") ||
            target.contains("?") ||
            target.contains("[")) {
            return FIELD_TAG;
        }

        return FIELD_LEMMA;
    }

    private SpanQuery applyConstraint(SpanQuery baseQuery, CQLPattern.Constraint constraint) {
        // Build the constraint query
        SpanQuery constraintQuery = buildConstraintQuery(constraint);

        // Check if base query and constraint are on the same field
        String baseField = getQueryField(baseQuery);
        String constraintField = getQueryField(constraintQuery);

        if (baseField.equals(constraintField)) {
            // Same field - use SpanContainingQuery
            if (constraint.isAnd()) {
                SpanQuery combined = baseQuery;
                for (CQLPattern.Constraint andConstraint : constraint.getAndConstraints()) {
                    SpanQuery andQuery = buildConstraintQuery(andConstraint);
                    combined = new SpanContainingQuery(combined, andQuery);
                }
                return combined;
            }

            if (constraint.isOr()) {
                List<SpanQuery> orQueries = new ArrayList<>();
                orQueries.add(baseQuery);
                for (CQLPattern.Constraint orConstraint : constraint.getOrConstraints()) {
                    orQueries.add(buildConstraintQuery(orConstraint));
                }
                return new SpanOrQuery(orQueries.toArray(new SpanQuery[0]));
            }

            return new SpanContainingQuery(baseQuery, constraintQuery);
        } else {
            // Different fields - for true multi-field AND, we need post-filtering
            // Return the base query only; constraint will be applied in post-processing
            if (constraint.isAnd()) {
                List<SpanQuery> orQueries = new ArrayList<>();
                orQueries.add(baseQuery);
                for (CQLPattern.Constraint andConstraint : constraint.getAndConstraints()) {
                    orQueries.add(buildConstraintQuery(andConstraint));
                }
                return new SpanOrQuery(orQueries.toArray(new SpanQuery[0]));
            }

            if (constraint.isOr()) {
                List<SpanQuery> orQueries = new ArrayList<>();
                orQueries.add(baseQuery);
                for (CQLPattern.Constraint orConstraint : constraint.getOrConstraints()) {
                    orQueries.add(buildConstraintQuery(orConstraint));
                }
                return new SpanOrQuery(orQueries.toArray(new SpanQuery[0]));
            }

            // For single constraint on different field, return base only
            // Post-filtering in application code will check the constraint
            return baseQuery;
        }
    }

    private SpanQuery buildConstraintQuery(CQLPattern.Constraint constraint) {
        String field = constraint.getField();
        String pattern = constraint.getPattern().replace("\"", "");

        SpanQuery query = buildFieldQuery(field, pattern);

        if (constraint.isNegated()) {
            // For negation on a different field, return just the query for post-filtering
            // True negation requires SpanNotQuery but needs same-field queries
            // Return the constraint query - caller will handle post-filtering
            return query;
        }

        return query;
    }

    private SpanQuery buildFieldQuery(String field, String pattern) {
        // Tags are stored lowercase in the index
        if (field.equalsIgnoreCase("tag")) {
            pattern = pattern.toLowerCase();
        }
        switch (field.toLowerCase()) {
            case "tag":
                return buildTermQuery(pattern, FIELD_TAG);
            case "word":
                return buildTermQuery(pattern, FIELD_WORD);
            case "lemma":
                return buildTermQuery(pattern, FIELD_LEMMA);
            case "pos_group":
                return buildTermQuery(pattern, FIELD_POS_GROUP);
            default:
                throw new IllegalArgumentException("Unknown constraint field: " + field);
        }
    }

    private int calculateTotalSlop(List<CQLPattern.PatternElement> elements) {
        int totalSlop = 0;
        for (CQLPattern.PatternElement element : elements) {
            if (element.getMaxDistance() > 0) {
                totalSlop += element.getMaxDistance();
            }
        }
        return totalSlop;
    }

    private boolean containsRegexMetacharacters(String s) {
        return s.contains(".*") || s.contains(".") ||
               s.contains("[") || s.contains("]") ||
               s.contains("(") || s.contains(")") ||
               s.contains("?") || s.contains("+") ||
               s.contains("^") || s.contains("$") ||
               s.contains("|");
    }

    /**
     * Result of compiling a full CQL with all features.
     */
    public static class CompilationResult {
        private final SpanQuery mainQuery;
        private final List<CQLPattern.AgreementRule> agreementRules;

        public CompilationResult(SpanQuery mainQuery, List<CQLPattern.AgreementRule> agreementRules) {
            this.mainQuery = mainQuery;
            this.agreementRules = agreementRules;
        }

        public SpanQuery getMainQuery() { return mainQuery; }
        public List<CQLPattern.AgreementRule> getAgreementRules() { return agreementRules; }
        public boolean hasAgreementRules() { return !agreementRules.isEmpty(); }
    }
}

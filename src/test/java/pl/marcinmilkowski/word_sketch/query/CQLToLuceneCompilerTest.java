package pl.marcinmilkowski.word_sketch.query;

import org.apache.lucene.queries.spans.SpanQuery;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanOrQuery;
import org.apache.lucene.queries.spans.SpanFirstQuery;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.grammar.CQLPattern;
import pl.marcinmilkowski.word_sketch.grammar.CQLParser;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CQLToLuceneCompiler.
 */
class CQLToLuceneCompilerTest {

    private final CQLToLuceneCompiler compiler = new CQLToLuceneCompiler();
    private final CQLParser parser = new CQLParser();

    @Test
    void testCompileSimplePattern() {
        CQLPattern pattern = parser.parse("\"N.*\"");
        SpanQuery query = compiler.compile(pattern);
        assertNotNull(query);
    }

    @Test
    void testCompileSequence() {
        CQLPattern pattern = parser.parse("1:\"JJ.*\" 2:\"N.*\"");
        SpanQuery query = compiler.compile(pattern);

        // Should create a SpanNearQuery for sequences
        assertTrue(query instanceof SpanNearQuery);
    }

    @Test
    void testEmptyPatternThrows() {
        CQLPattern pattern = new CQLPattern();

        assertThrows(IllegalArgumentException.class, () -> {
            compiler.compile(pattern);
        });
    }

    @Test
    void testCompilerSingleton() {
        CQLToLuceneCompiler compiler1 = new CQLToLuceneCompiler();
        CQLToLuceneCompiler compiler2 = new CQLToLuceneCompiler();

        CQLPattern pattern = parser.parse("\"VB.*\"");
        assertNotNull(compiler1.compile(pattern));
        assertNotNull(compiler2.compile(pattern));
    }

    @Test
    void testCompileMultiplePositions() {
        String cql = "1:\"V.*\" 2:\"N.*\"";
        CQLPattern pattern = parser.parse(cql);
        SpanQuery query = compiler.compile(pattern);

        assertNotNull(query);
        assertTrue(query instanceof SpanNearQuery);
    }

    @Test
    void testRegexOrPattern() {
        // Test that regex OR patterns (a|b|c) are converted to SpanOrQuery
        String cql = "[word=\"be|remain|seem\"]";
        CQLPattern pattern = parser.parse(cql);
        SpanQuery query = compiler.compile(pattern);

        assertNotNull(query);
        // The compiled query should be a SpanOrQuery with 3 alternatives
        assertTrue(query instanceof SpanOrQuery, "Expected SpanOrQuery for regex OR pattern");
        SpanOrQuery orQuery = (SpanOrQuery) query;
        assertEquals(3, orQuery.getClauses().length, "Expected 3 alternatives for 'be|remain|seem'");
    }

    @Test
    void testSnowballLinkingVerbPattern() {
        // Test the actual snowball pattern with many alternatives
        // Pattern has two different fields: word and tag
        String cql = "[word=\"be|remain|seem|appear|feel|get|become|look|smell|taste\"] [tag=\"JJ.*\"]";
        CQLPattern pattern = parser.parse(cql);
        SpanQuery query = compiler.compile(pattern);

        assertNotNull(query);
        // Multi-field patterns return queries from the first field only
        // The other field's constraints are checked via post-filtering
        // So we should get a query on the word field with OR alternatives
        assertTrue(query instanceof SpanOrQuery, "Expected SpanOrQuery for word field");
        SpanOrQuery wordOrQuery = (SpanOrQuery) query;
        assertEquals(10, wordOrQuery.getClauses().length, "Expected 10 linking verb alternatives");
    }
}


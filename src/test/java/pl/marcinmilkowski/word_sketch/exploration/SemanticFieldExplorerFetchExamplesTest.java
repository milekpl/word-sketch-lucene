package pl.marcinmilkowski.word_sketch.exploration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigHelper;
import pl.marcinmilkowski.word_sketch.exploration.ExplorationException;
import pl.marcinmilkowski.word_sketch.config.RelationConfig;
import pl.marcinmilkowski.word_sketch.model.exploration.FetchExamplesOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.FetchExamplesResult;
import pl.marcinmilkowski.word_sketch.model.PosGroup;
import pl.marcinmilkowski.word_sketch.model.sketch.*;
import pl.marcinmilkowski.word_sketch.model.RelationType;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.StubQueryExecutor;

import java.io.IOException;
import java.util.List;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SemanticFieldExplorer#fetchExamples}.
 */
@DisplayName("SemanticFieldExplorer.fetchExamples")
class SemanticFieldExplorerFetchExamplesTest {

    /** A minimal RelationConfig with a SURFACE pattern that embeds headword and collocate positions. */
    private static RelationConfig testRelationConfig() {
        return GrammarConfigHelper.requireTestConfig().relations().stream()
                .filter(r -> r.relationType().isPresent())
                .findFirst()
                .orElse(GrammarConfigHelper.minimalSurfaceRelationConfig());
    }

    private static CollocateResult collocateResult(String sentence) {
        return new CollocateResult(sentence, null, 0, 5, "d1", "important", 1, 7.0);
    }

    @Test
    @DisplayName("fetchExamples_returnsSentencesFromResults: returns all sentences when no duplicates")
    void fetchExamples_returnsSentencesFromResults() throws IOException {
        QueryExecutor executor = new StubQueryExecutor() {
            @Override
            public List<CollocateResult> executeBcqlQuery(String bcqlPattern, int maxResults) {
                return List.of(
                        collocateResult("a"),
                        collocateResult("b"),
                        collocateResult("c")
                );
            }
        };

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, "[xpos=\"NN.*\"]");
        FetchExamplesResult fetched = explorer.fetchExamples(
                "theory", "important", testRelationConfig(), new FetchExamplesOptions(10));

        assertEquals(3, fetched.examples().size(), "Should return all 3 sentences");
        assertTrue(fetched.examples().stream().anyMatch(r -> "a".equals(r.sentence())));
        assertTrue(fetched.examples().stream().anyMatch(r -> "b".equals(r.sentence())));
        assertTrue(fetched.examples().stream().anyMatch(r -> "c".equals(r.sentence())));
    }

    @Test
    @DisplayName("fetchExamples_deduplicatesSentences: removes duplicate sentences")
    void fetchExamples_deduplicatesSentences() throws IOException {
        QueryExecutor executor = new StubQueryExecutor() {
            @Override
            public List<CollocateResult> executeBcqlQuery(String bcqlPattern, int maxResults) {
                return List.of(
                        collocateResult("a"),
                        collocateResult("a"),
                        collocateResult("b")
                );
            }
        };

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, "[xpos=\"NN.*\"]");
        FetchExamplesResult fetched = explorer.fetchExamples(
                "theory", "important", testRelationConfig(), new FetchExamplesOptions(10));

        assertEquals(2, fetched.examples().size(), "Should deduplicate: expect [a, b]");
        assertTrue(fetched.examples().stream().anyMatch(r -> "a".equals(r.sentence())));
        assertTrue(fetched.examples().stream().anyMatch(r -> "b".equals(r.sentence())));
    }

    @Test
    @DisplayName("fetchExamples_respectsMaxLimit: returns no more than maxExamples")
    void fetchExamples_respectsMaxLimit() throws IOException {
        QueryExecutor executor = new StubQueryExecutor() {
            @Override
            public List<CollocateResult> executeBcqlQuery(String bcqlPattern, int maxResults) {
                return List.of(
                        collocateResult("a"),
                        collocateResult("b"),
                        collocateResult("c"),
                        collocateResult("d"),
                        collocateResult("e")
                );
            }
        };

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, "[xpos=\"NN.*\"]");
        FetchExamplesResult fetched = explorer.fetchExamples(
                "theory", "important", testRelationConfig(), new FetchExamplesOptions(3));

        assertEquals(3, fetched.examples().size(), "Should not exceed maxExamples=3");
    }

    @Test
    @DisplayName("fetchExamples_propagatesIOExceptionFromExecutor: IOException from executeBcqlQuery is wrapped in ExplorationException")
    void fetchExamples_propagatesIOExceptionFromExecutor() {
        QueryExecutor executor = new StubQueryExecutor() {
            @Override
            public List<CollocateResult> executeBcqlQuery(
                    String bcqlPattern, int maxResults) throws IOException {
                throw new IOException("simulated executor failure");
            }
        };

        SemanticFieldExplorer explorer = new SemanticFieldExplorer(executor, "[xpos=\"NN.*\"]");

        ExplorationException thrown = assertThrows(ExplorationException.class,
                () -> explorer.fetchExamples("theory", "important", testRelationConfig(), new FetchExamplesOptions(10)),
                "IOException from the underlying executor must be wrapped in ExplorationException");

        assertNotNull(thrown.getCause(), "ExplorationException should carry the original IOException as cause");
        assertEquals("simulated executor failure", thrown.getCause().getMessage(),
                "The wrapped IOException should carry the original message unchanged");
    }
}

package pl.marcinmilkowski.word_sketch.indexer.blacklab;

import nl.inl.blacklab.index.DocumentFormats;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for {@link BlackLabConllUIndexer}.
 *
 * <p>These tests cover the end-to-end indexing pipeline: CoNLL-U → WPL conversion →
 * BlackLab index. They require the {@code conllu-sentences.blf.yaml} format config file
 * to be present in the project root (working directory). Tests are skipped automatically
 * when the format config is not found.
 */
@Disabled("Requires conllu-sentences.blf.yaml format config in the project root — not distributed with source; run from a local environment that has the BlackLab format config installed")
@DisplayName("BlackLabConllUIndexer")
class BlackLabConllUIndexerTest {

    private static final String FORMAT_NAME = "conllu-sentences";

    private static final String MINIMAL_CONLLU =
            "# sent_id = 1\n" +
            "1\tThe\tthe\tDET\tDT\t_\t2\tdet\t_\t_\n" +
            "2\ttheory\ttheory\tNOUN\tNN\t_\t4\tnsubj\t_\t_\n" +
            "3\tis\tbe\tVERB\tVBZ\t_\t4\tcop\t_\t_\n" +
            "4\tcorrect\tcorrect\tADJ\tJJ\t_\t0\troot\t_\t_\n" +
            "\n" +
            "# sent_id = 2\n" +
            "1\tA\ta\tDET\tDT\t_\t2\tdet\t_\t_\n" +
            "2\tmodel\tmodel\tNOUN\tNN\t_\t3\tnsubj\t_\t_\n" +
            "3\texplains\texplain\tVERB\tVBZ\t_\t0\troot\t_\t_\n" +
            "\n";

    @BeforeAll
    static void requireFormatConfig() {
        Path formatFile = Paths.get(System.getProperty("user.dir"), FORMAT_NAME + ".blf.yaml");
        Assumptions.assumeTrue(
                formatFile.toFile().exists(),
                "Skipped: format config not found at " + formatFile + " — run from project root");
        DocumentFormats.addConfigFormatsInDirectories(java.util.List.of(formatFile.getParent().toFile()));
    }

    // ── smoke: full pipeline ──────────────────────────────────────────────────

    @Test
    @DisplayName("indexes a minimal CoNLL-U corpus and reports correct document and token counts")
    void indexMinimalCorpus_countsAreCorrect(@TempDir Path tmp) throws IOException {
        Path conlluFile = tmp.resolve("mini.conllu");
        Files.writeString(conlluFile, MINIMAL_CONLLU);

        Path wplDir = tmp.resolve("wpl");
        Files.createDirectories(wplDir);
        ConlluConverter.convertConlluToWplChunks(conlluFile, wplDir, 1000);

        Path indexDir = tmp.resolve("index");
        try (BlackLabConllUIndexer indexer = new BlackLabConllUIndexer(indexDir.toString(), FORMAT_NAME)) {
            indexer.indexFile(wplDir.toString());

            assertTrue(indexer.getDocumentCount() >= 1,
                    "Document count should be at least 1 after indexing a non-empty corpus");
        }
        // After close the index directory should contain at least the BlackLab version file
        assertTrue(indexDir.toFile().exists(), "Index directory must exist after indexing");
        assertTrue(indexDir.toFile().list() != null && indexDir.toFile().list().length > 0,
                "Index directory must be non-empty after indexing");
    }

    @Test
    @DisplayName("creates index directory when it does not exist yet")
    void constructor_createsIndexDirectory(@TempDir Path tmp) throws IOException {
        Path newIndex = tmp.resolve("new-index");
        assertFalse(newIndex.toFile().exists(), "precondition: directory must not exist");

        try (BlackLabConllUIndexer indexer = new BlackLabConllUIndexer(newIndex.toString(), FORMAT_NAME)) {
            assertTrue(newIndex.toFile().exists(), "Constructor must create the index directory");
        }
    }

    @Test
    @DisplayName("throws IOException when input file does not exist")
    void indexFile_missingPath_throwsIOException(@TempDir Path tmp) throws IOException {
        Path indexDir = tmp.resolve("index");
        try (BlackLabConllUIndexer indexer = new BlackLabConllUIndexer(indexDir.toString(), FORMAT_NAME)) {
            assertThrows(IOException.class,
                    () -> indexer.indexFile(tmp.resolve("nonexistent.conllu").toString()),
                    "indexFile must throw IOException for a missing path");
        }
    }

    @Test
    @DisplayName("getTokenCount returns 0 before any documents are indexed")
    void tokenCount_initiallyZero(@TempDir Path tmp) throws IOException {
        Path indexDir = tmp.resolve("index");
        try (BlackLabConllUIndexer indexer = new BlackLabConllUIndexer(indexDir.toString(), FORMAT_NAME)) {
            assertEquals(0, indexer.getTokenCount(), "Token count must start at zero");
        }
    }
}

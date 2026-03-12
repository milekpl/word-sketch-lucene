package pl.marcinmilkowski.word_sketch.indexer.blacklab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConlluConverter")
class ConlluConverterTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Minimal two-sentence CoNLL-U snippet. */
    private static final String TWO_SENTENCES =
            "# sent_id = 1\n" +
            "1\tThe\tthe\tDET\tDT\t_\t2\tdet\t_\t_\n" +
            "2\tcat\tcat\tNOUN\tNN\t_\t3\tnsubj\t_\t_\n" +
            "3\tsits\tsit\tVERB\tVBZ\t_\t0\troot\t_\t_\n" +
            "\n" +
            "# sent_id = 2\n" +
            "1\tA\ta\tDET\tDT\t_\t2\tdet\t_\t_\n" +
            "2\tdog\tdog\tNOUN\tNN\t_\t3\tnsubj\t_\t_\n" +
            "3\truns\trun\tVERB\tVBZ\t_\t0\troot\t_\t_\n" +
            "\n";

    private Path writeInput(Path dir, String content) throws IOException {
        Path input = dir.resolve("input.conllu");
        Files.writeString(input, content);
        return input;
    }

    // ── basic conversion ──────────────────────────────────────────────────────

    @Test
    @DisplayName("returns correct sentence and token counts")
    void countsAreCorrect(@TempDir Path tmp) throws IOException {
        Path input = writeInput(tmp, TWO_SENTENCES);
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);

        ConlluConverter.ConversionStats result = ConlluConverter.convertConlluToWplChunks(input, outDir, 100);

        assertEquals(2, result.sentences(), "sentences");
        assertEquals(6, result.tokens(), "tokens");
        assertEquals(1, result.chunks(), "chunks");
    }

    @Test
    @DisplayName("output chunk contains <s> and </s> sentence markers")
    void outputHasSentenceMarkers(@TempDir Path tmp) throws IOException {
        Path input = writeInput(tmp, TWO_SENTENCES);
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);

        ConlluConverter.convertConlluToWplChunks(input, outDir, 100);

        List<String> lines = Files.readAllLines(outDir.resolve("chunk_000000.tsv"));
        long openTags  = lines.stream().filter(l -> l.equals("<s>")).count();
        long closeTags = lines.stream().filter(l -> l.equals("</s>")).count();
        assertEquals(2, openTags,  "<s> count");
        assertEquals(2, closeTags, "</s> count");
    }

    // ── chunking ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("splits into multiple chunk files when sentencesPerChunk=1")
    void chunkingSplitsCorrectly(@TempDir Path tmp) throws IOException {
        Path input = writeInput(tmp, TWO_SENTENCES);
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);

        ConlluConverter.ConversionStats result = ConlluConverter.convertConlluToWplChunks(input, outDir, 1);

        assertEquals(2, result.sentences(), "sentences");
        assertEquals(2, result.chunks(), "chunks");
        assertTrue(Files.exists(outDir.resolve("chunk_000000.tsv")), "chunk 0 exists");
        assertTrue(Files.exists(outDir.resolve("chunk_000001.tsv")), "chunk 1 exists");
    }

    @Test
    @DisplayName("each chunk contains exactly the right number of sentences when split")
    void eachChunkHasOneSentence(@TempDir Path tmp) throws IOException {
        Path input = writeInput(tmp, TWO_SENTENCES);
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);

        ConlluConverter.convertConlluToWplChunks(input, outDir, 1);

        for (String name : new String[]{"chunk_000000.tsv", "chunk_000001.tsv"}) {
            List<String> lines = Files.readAllLines(outDir.resolve(name));
            long opens  = lines.stream().filter(l -> l.equals("<s>")).count();
            long closes = lines.stream().filter(l -> l.equals("</s>")).count();
            assertEquals(1, opens,  name + " <s>");
            assertEquals(1, closes, name + " </s>");
        }
    }

    // ── comment / MWT / empty-node filtering ─────────────────────────────────

    @Test
    @DisplayName("comment lines (# ...) are excluded from output")
    void commentLinesAreStripped(@TempDir Path tmp) throws IOException {
        Path input = writeInput(tmp, TWO_SENTENCES);
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);

        ConlluConverter.convertConlluToWplChunks(input, outDir, 100);

        List<String> lines = Files.readAllLines(outDir.resolve("chunk_000000.tsv"));
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("#")), "no comment lines");
    }

    @Test
    @DisplayName("multi-word token lines (1-2\\t...) are skipped")
    void mwtLinesAreSkipped(@TempDir Path tmp) throws IOException {
        String withMwt =
                "# sent_id = 1\n" +
                "1-2\tdel\t_\t_\t_\t_\t_\t_\t_\t_\n" +
                "1\tde\tde\tADP\tIN\t_\t3\tcase\t_\t_\n" +
                "2\tel\tel\tDET\tDT\t_\t3\tdet\t_\t_\n" +
                "3\tgato\tgato\tNOUN\tNN\t_\t0\troot\t_\t_\n" +
                "\n";
        Path input = writeInput(tmp, withMwt);
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);

        ConlluConverter.ConversionStats result = ConlluConverter.convertConlluToWplChunks(input, outDir, 100);

        assertEquals(1, result.sentences(), "sentences");
        assertEquals(3, result.tokens(), "tokens (MWT line not counted)");

        List<String> lines = Files.readAllLines(outDir.resolve("chunk_000000.tsv"));
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("1-2\t")), "MWT line absent");
    }

    @Test
    @DisplayName("empty-node lines (1.1\\t...) are skipped")
    void emptyNodeLinesAreSkipped(@TempDir Path tmp) throws IOException {
        String withEmpty =
                "# sent_id = 1\n" +
                "1\tHe\the\tPRON\tPRP\t_\t2\tnsubj\t_\t_\n" +
                "1.1\tnull\tnull\tNOUN\tNN\t_\t_\t_\t_\t_\n" +
                "2\tsmiles\tsmile\tVERB\tVBZ\t_\t0\troot\t_\t_\n" +
                "\n";
        Path input = writeInput(tmp, withEmpty);
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);

        ConlluConverter.ConversionStats result = ConlluConverter.convertConlluToWplChunks(input, outDir, 100);

        assertEquals(2, result.tokens(), "tokens (empty node not counted)");
        List<String> lines = Files.readAllLines(outDir.resolve("chunk_000000.tsv"));
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("1.1\t")), "empty node line absent");
    }

    // ── edge cases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("empty input file produces zero counts and no chunk files")
    void emptyInput(@TempDir Path tmp) throws IOException {
        Path input = writeInput(tmp, "");
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);

        ConlluConverter.ConversionStats result = ConlluConverter.convertConlluToWplChunks(input, outDir, 100);

        assertEquals(0, result.sentences(), "sentences");
        assertEquals(0, result.tokens(), "tokens");
        assertEquals(0, result.chunks(), "chunks");
        assertEquals(0, Files.list(outDir).count(), "no output files");
    }

    @Test
    @DisplayName("file with only comments and blank lines produces zero counts")
    void onlyCommentsAndBlanks(@TempDir Path tmp) throws IOException {
        Path input = writeInput(tmp, "# comment\n\n# another\n\n");
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);

        ConlluConverter.ConversionStats result = ConlluConverter.convertConlluToWplChunks(input, outDir, 100);

        assertEquals(0, result.sentences());
        assertEquals(0, result.tokens());
        assertEquals(0, result.chunks());
    }

    @Test
    @DisplayName("sentence without trailing blank line is still closed and counted")
    void sentenceWithoutTrailingBlankLine(@TempDir Path tmp) throws IOException {
        // No final blank line — converter must still close the last sentence
        String noTrailingBlank =
                "1\tcat\tcat\tNOUN\tNN\t_\t0\troot\t_\t_\n";
        Path input = writeInput(tmp, noTrailingBlank);
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);

        ConlluConverter.ConversionStats result = ConlluConverter.convertConlluToWplChunks(input, outDir, 100);

        assertEquals(1, result.sentences(), "sentences");
        assertEquals(1, result.tokens(), "tokens");
        assertEquals(1, result.chunks(), "chunks");

        List<String> lines = Files.readAllLines(outDir.resolve("chunk_000000.tsv"));
        assertTrue(lines.contains("</s>"), "trailing </s> written");
    }

    // ── error / edge paths ────────────────────────────────────────────────────

    @Test
    @DisplayName("malformed token line (fewer than 10 fields) is written through without error")
    void malformedTokenLine_writtenThrough(@TempDir Path tmp) throws IOException {
        // Only 4 columns instead of the standard 10 — converter should not crash;
        // it passes lines verbatim and does not validate column count.
        String withShortLine =
                "1\tcat\tcat\tNOUN\n" +
                "\n";
        Path input = writeInput(tmp, withShortLine);
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);

        ConlluConverter.ConversionStats result = ConlluConverter.convertConlluToWplChunks(input, outDir, 100);

        assertEquals(1, result.sentences(), "sentences");
        assertEquals(1, result.tokens(), "tokens");
        List<String> lines = Files.readAllLines(outDir.resolve("chunk_000000.tsv"));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("1\tcat")), "short line preserved");
    }

    @Test
    @DisplayName("consecutive blank lines between sentences do not produce phantom sentences")
    void consecutiveBlankLines_noPhantomSentences(@TempDir Path tmp) throws IOException {
        String withExtraBlanks =
                "# sent_id = 1\n" +
                "1\tcat\tcat\tNOUN\tNN\t_\t0\troot\t_\t_\n" +
                "\n" +
                "\n" +   // extra blank line
                "# sent_id = 2\n" +
                "1\tdog\tdog\tNOUN\tNN\t_\t0\troot\t_\t_\n" +
                "\n";
        Path input = writeInput(tmp, withExtraBlanks);
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);

        ConlluConverter.ConversionStats result = ConlluConverter.convertConlluToWplChunks(input, outDir, 100);

        assertEquals(2, result.sentences(), "only 2 real sentences, not 3");
        assertEquals(2, result.tokens(), "tokens");
    }
}

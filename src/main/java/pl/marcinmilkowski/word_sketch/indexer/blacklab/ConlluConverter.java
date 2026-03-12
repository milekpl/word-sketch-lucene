package pl.marcinmilkowski.word_sketch.indexer.blacklab;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Converts CoNLL-U format to tabular WPL with &lt;s&gt; markers, split into chunk files.
 * BlackLab's tabular indexer loads each file fully into memory, so large corpora
 * must be split into manageable chunks (one file = one BlackLab document).
 *
 * <p>Two concerns are at work here:
 * <ul>
 *   <li><strong>CoNLL-U parsing</strong>: skipping comment/MWT lines, emitting {@code <s>}
 *       sentence markers, and tracking in-sentence state.</li>
 *   <li><strong>Chunk rotation</strong>: closing the current chunk writer and opening the next
 *       when a sentence boundary is reached and the per-chunk sentence quota is exceeded.
 *       This concern is encapsulated in {@link #rotateChunkIfNeeded}.</li>
 * </ul>
 * </p>
 */
public class ConlluConverter {

    private static final Pattern MWT_OR_EMPTY = Pattern.compile("^\\d+-\\d+\t|^\\d+\\.\\d+\t");

    // state[] indices: 0=sentences, 1=tokens, 2=chunks
    private static final int STATE_SENTENCES = 0;
    private static final int STATE_TOKENS = 1;
    private static final int STATE_CHUNKS = 2;

    /** Counts returned from {@link #convertConlluToWplChunks}. */
    public record ConversionStats(long sentences, long tokens, long chunks) {}

    private ConlluConverter() {}

    /**
     * Converts a CoNLL-U file to WPL chunk files in the given output directory.
     *
     * @return {@link ConversionStats} with sentence, token, and chunk-file counts
     */
    public static ConversionStats convertConlluToWplChunks(Path input, Path outputDir, int sentencesPerChunk)
            throws IOException {
        long[] state = {0, 0, 0}; // [sentences, tokens, chunks]
        @Nullable BufferedWriter writer = null;

        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            writer = writeWplChunksFromConllu(reader, outputDir, sentencesPerChunk, state);
        } finally {
            if (writer != null) {
                try (BufferedWriter toClose = writer) {
                }
                state[STATE_CHUNKS]++;
            }
        }
        return new ConversionStats(state[STATE_SENTENCES], state[STATE_TOKENS], state[STATE_CHUNKS]);
    }

    /**
     * Reads all lines from {@code reader} and writes sentence-annotated WPL tokens to
     * rotating chunk files. Returns the writer for the last open chunk (if any), so the
     * caller can close it and increment the chunk counter.
     *
     * <p>Invariant: {@code inSentence} implies {@code writer != null}. A new chunk writer
     * is opened at the start of every sentence, so any open sentence always has a writer.
     */
    private static @Nullable BufferedWriter writeWplChunksFromConllu(
            BufferedReader reader, Path outputDir, int sentencesPerChunk, long[] state)
            throws IOException {
        boolean inSentence = false;
        int sentencesInChunk = 0;
        @Nullable BufferedWriter writer = null;

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                if (line.isBlank()) {
                    if (inSentence) {
                        // inSentence implies writer != null (see invariant in Javadoc)
                        writer.write("</s>\n");
                        state[STATE_SENTENCES]++;
                        sentencesInChunk++;
                        inSentence = false;
                        Optional<Long> rotation = rotateChunkIfNeeded(writer, outputDir, state[STATE_CHUNKS], sentencesInChunk, sentencesPerChunk);
                        if (rotation.isPresent()) {
                            writer = null;
                            state[STATE_CHUNKS] = rotation.get();
                            sentencesInChunk = 0;
                        }
                    }
                    continue;
                }
                if (MWT_OR_EMPTY.matcher(line).find()) continue;
                if (!inSentence) {
                    if (writer == null) {
                        writer = openChunk(outputDir, state[STATE_CHUNKS]);
                    }
                    writer.write("<s>\n");
                    inSentence = true;
                }
                writer.write(line);
                writer.write('\n');
                state[STATE_TOKENS]++;
            }
            if (inSentence && writer != null) {
                writer.write("</s>\n");
                state[STATE_SENTENCES]++;
            }
        } catch (IOException e) {
            // Close the writer before re-throwing so resources are not leaked.
            if (writer != null) {
                try { writer.close(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
                writer = null;
            }
            throw e;
        }
        return writer;
    }

    /**
     * Closes the current chunk writer and increments the chunk counter when the per-chunk
     * sentence quota is reached. Returns the new chunk count wrapped in {@link Optional} when
     * rotation occurred (caller should reset {@code sentencesInChunk} to 0 and set writer to null),
     * or {@link Optional#empty()} when no rotation was needed.
     */
    private static Optional<Long> rotateChunkIfNeeded(
            BufferedWriter writer, Path outputDir, long chunks,
            int sentencesInChunk, int sentencesPerChunk) throws IOException {
        if (sentencesInChunk < sentencesPerChunk) return Optional.empty();
        writer.close();
        return Optional.of(chunks + 1);
    }

    private static BufferedWriter openChunk(Path outputDir, long index) throws IOException {
        Path chunk = outputDir.resolve(String.format("chunk_%06d.tsv", index));
        return Files.newBufferedWriter(chunk, StandardCharsets.UTF_8);
    }
}

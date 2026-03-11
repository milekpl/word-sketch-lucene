package pl.marcinmilkowski.word_sketch.indexer.blacklab;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

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

    private ConlluConverter() {}

    /**
     * Converts a CoNLL-U file to WPL chunk files in the given output directory.
     *
     * @return long[]{sentenceCount, tokenCount, chunkCount}
     */
    public static long[] convertConlluToWplChunks(Path input, Path outputDir, int sentencesPerChunk)
            throws IOException {
        long sentences = 0, tokens = 0, chunks = 0;
        boolean inSentence = false;
        int sentencesInChunk = 0;
        BufferedWriter writer = null;

        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#")) continue;
                    if (line.isBlank()) {
                        if (inSentence) {
                            writer.write("</s>\n");
                            sentences++;
                            sentencesInChunk++;
                            inSentence = false;
                            long[] rotated = rotateChunkIfNeeded(writer, outputDir, chunks, sentencesInChunk, sentencesPerChunk);
                            if (rotated != null) {
                                writer = null;
                                chunks = rotated[0];
                                sentencesInChunk = 0;
                            }
                        }
                        continue;
                    }
                    if (MWT_OR_EMPTY.matcher(line).find()) continue;
                    if (!inSentence) {
                        if (writer == null) {
                            writer = openChunk(outputDir, chunks);
                        }
                        writer.write("<s>\n");
                        inSentence = true;
                    }
                    writer.write(line);
                    writer.write('\n');
                    tokens++;
                }
                if (inSentence && writer != null) {
                    writer.write("</s>\n");
                    sentences++;
                }
            } finally {
                if (writer != null) {
                    // On the error path the in-progress sentence is left open deliberately:
                    // writing a partial </s> tag would produce a malformed chunk that is
                    // harder to detect than an abrupt EOF. The try-with-resources below
                    // ensures the writer is always closed (flushing any buffered bytes) even
                    // if close() itself throws.
                    // NOTE: chunks++ runs unconditionally after close, so every chunk file
                    // that was opened (including error-path chunks) is counted.
                    try (BufferedWriter toClose = writer) {
                    }
                    chunks++;
                }
            }
        }
        return new long[]{sentences, tokens, chunks};
    }

    /**
     * Closes the current chunk writer and increments the chunk counter when the per-chunk
     * sentence quota is reached. Returns a {@code long[]{newChunkCount}} when rotation
     * occurred (caller should reset {@code sentencesInChunk} to 0 and set writer to null),
     * or {@code null} when no rotation was needed.
     */
    private static long[] rotateChunkIfNeeded(
            BufferedWriter writer, Path outputDir, long chunks,
            int sentencesInChunk, int sentencesPerChunk) throws IOException {
        if (sentencesInChunk < sentencesPerChunk) return null;
        writer.close();
        return new long[]{chunks + 1};
    }

    private static BufferedWriter openChunk(Path outputDir, long index) throws IOException {
        Path chunk = outputDir.resolve(String.format("chunk_%06d.tsv", index));
        return Files.newBufferedWriter(chunk, StandardCharsets.UTF_8);
    }
}

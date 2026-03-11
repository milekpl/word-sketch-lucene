package pl.marcinmilkowski.word_sketch.indexer.blacklab;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
        Optional<BufferedWriter> writerOpt = Optional.empty();

        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#")) continue;
                    if (line.isBlank()) {
                        if (inSentence) {
                            writerOpt.get().write("</s>\n");
                            sentences++;
                            sentencesInChunk++;
                            inSentence = false;
                            Optional<long[]> rotated = rotateChunkIfNeeded(writerOpt.get(), outputDir, chunks, sentencesInChunk, sentencesPerChunk);
                            if (rotated.isPresent()) {
                                writerOpt = Optional.empty();
                                chunks = rotated.get()[0];
                                sentencesInChunk = 0;
                            }
                        }
                        continue;
                    }
                    if (MWT_OR_EMPTY.matcher(line).find()) continue;
                    if (!inSentence) {
                        if (writerOpt.isEmpty()) {
                            writerOpt = Optional.of(openChunk(outputDir, chunks));
                        }
                        writerOpt.get().write("<s>\n");
                        inSentence = true;
                    }
                    writerOpt.get().write(line);
                    writerOpt.get().write('\n');
                    tokens++;
                }
                if (inSentence && writerOpt.isPresent()) {
                    writerOpt.get().write("</s>\n");
                    sentences++;
                }
            } finally {
                if (writerOpt.isPresent()) {
                    // On the error path the in-progress sentence is left open deliberately:
                    // writing a partial </s> tag would produce a malformed chunk that is
                    // harder to detect than an abrupt EOF. The try-with-resources below
                    // ensures the writer is always closed (flushing any buffered bytes) even
                    // if close() itself throws.
                    // NOTE: chunks++ runs unconditionally after close, so every chunk file
                    // that was opened (including error-path chunks) is counted.
                    try (BufferedWriter toClose = writerOpt.get()) {
                    }
                    chunks++;
                }
            }
        }
        return new long[]{sentences, tokens, chunks};
    }

    /**
     * Closes the current chunk writer and increments the chunk counter when the per-chunk
     * sentence quota is reached. Returns {@code Optional.of(new long[]{newChunkCount})} when
     * rotation occurred (caller should reset {@code sentencesInChunk} to 0 and set writer to null),
     * or {@code Optional.empty()} when no rotation was needed.
     */
    private static Optional<long[]> rotateChunkIfNeeded(
            BufferedWriter writer, Path outputDir, long chunks,
            int sentencesInChunk, int sentencesPerChunk) throws IOException {
        if (sentencesInChunk < sentencesPerChunk) return Optional.empty();
        writer.close();
        return Optional.of(new long[]{chunks + 1});
    }

    private static BufferedWriter openChunk(Path outputDir, long index) throws IOException {
        Path chunk = outputDir.resolve(String.format("chunk_%06d.tsv", index));
        return Files.newBufferedWriter(chunk, StandardCharsets.UTF_8);
    }
}

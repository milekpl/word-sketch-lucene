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
                            if (sentencesInChunk >= sentencesPerChunk) {
                                writer.close();
                                writer = null;
                                chunks++;
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
                    // if close() itself throws, in which case chunks is NOT incremented so
                    // callers can detect the truncated output.
                    try (BufferedWriter toClose = writer) {
                        // close flushes and releases the file handle
                    }
                    chunks++;
                }
            }
        }
        return new long[]{sentences, tokens, chunks};
    }

    private static BufferedWriter openChunk(Path outputDir, long index) throws IOException {
        Path chunk = outputDir.resolve(String.format("chunk_%06d.tsv", index));
        return Files.newBufferedWriter(chunk, StandardCharsets.UTF_8);
    }
}

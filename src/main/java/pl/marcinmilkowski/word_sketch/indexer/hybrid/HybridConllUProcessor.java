package pl.marcinmilkowski.word_sketch.indexer.hybrid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Processor for creating hybrid index from CoNLL-U formatted corpus.
 * 
 * Each sentence becomes one Lucene document (instead of token-per-document).
 * This enables faster queries and smaller index size.
 * 
 * Usage:
 * <pre>
 *   try (HybridConllUProcessor processor = new HybridConllUProcessor(indexPath)) {
 *       processor.processFile("corpus.conllu");
 *       // Or process directory of files:
 *       processor.processDirectory("corpus_dir/", "*.conllu");
 *   }
 * </pre>
 */
public class HybridConllUProcessor implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(HybridConllUProcessor.class);

    private final HybridIndexer indexer;
    private final AtomicInteger sentenceId = new AtomicInteger(0);
    private final AtomicLong tokenCount = new AtomicLong(0);
    private int commitInterval = 50_000;  // Commit every N sentences

    /**
     * Create a processor that writes to a hybrid index.
     * 
     * @param indexPath Path for the hybrid index
     */
    public HybridConllUProcessor(String indexPath) throws IOException {
        this.indexer = new HybridIndexer(indexPath);
        logger.info("HybridConllUProcessor initialized, output: {}", indexPath);
    }

    /**
     * Set the commit interval (number of sentences between commits).
     */
    public void setCommitInterval(int interval) {
        this.commitInterval = interval;
    }

    /**
     * Process a single CoNLL-U file.
     */
    public void processFile(String inputFile) throws IOException {
        Path path = Paths.get(inputFile);
        logger.info("Processing CoNLL-U file: {}", inputFile);
        
        long fileStartTime = System.currentTimeMillis();
        int fileSentenceCount = 0;
        long fileTokenCount = 0;

        // Use lenient UTF-8 decoder
        var decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .replaceWith("?");
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(path), decoder))) {
            
            List<String[]> currentTokens = new ArrayList<>();
            String currentText = "";

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) {
                    // End of sentence
                    if (!currentTokens.isEmpty()) {
                        SentenceDocument sentence = buildSentence(currentTokens, currentText);
                        indexer.indexSentence(sentence);
                        
                        fileSentenceCount++;
                        fileTokenCount += currentTokens.size();
                        tokenCount.addAndGet(currentTokens.size());

                        int totalSentences = sentenceId.get();
                        if (totalSentences > 0 && totalSentences % commitInterval == 0) {
                            indexer.commit();
                            long elapsed = System.currentTimeMillis() - fileStartTime;
                            double rate = tokenCount.get() / (elapsed / 1000.0);
                            logger.info("Progress: {} sentences, {} tokens ({} tok/s)",
                                totalSentences, tokenCount.get(), String.format("%.0f", rate));
                        }

                        currentTokens.clear();
                        currentText = "";
                    }
                } else if (line.startsWith("#")) {
                    // Comment line - extract sentence text
                    if (line.startsWith("# text =")) {
                        currentText = line.substring("# text =".length()).trim();
                    }
                } else {
                    // Token line
                    String[] fields = line.split("\t");
                    if (fields.length >= 4) {
                        currentTokens.add(fields);
                    }
                }
            }

            // Process remaining sentence
            if (!currentTokens.isEmpty()) {
                SentenceDocument sentence = buildSentence(currentTokens, currentText);
                indexer.indexSentence(sentence);
                fileSentenceCount++;
                fileTokenCount += currentTokens.size();
                tokenCount.addAndGet(currentTokens.size());
            }
        }

        // Commit after each file
        indexer.commit();

        long elapsed = System.currentTimeMillis() - fileStartTime;
        logger.info("File complete: {} sentences, {} tokens in {}s",
            fileSentenceCount, fileTokenCount, elapsed / 1000);
    }

    /**
     * Process all CoNLL-U files in a directory.
     * 
     * @param directory Path to directory
     * @param pattern Glob pattern (e.g., "*.conllu")
     */
    public void processDirectory(String directory, String pattern) throws IOException {
        Path dir = Paths.get(directory);
        logger.info("Processing directory: {} (pattern: {})", directory, pattern);

        long startTime = System.currentTimeMillis();
        int fileCount = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, pattern)) {
            List<Path> files = new ArrayList<>();
            stream.forEach(files::add);
            files.sort(Comparator.comparing(Path::getFileName));

            logger.info("Found {} files to process", files.size());

            for (Path file : files) {
                fileCount++;
                logger.info("Processing file {}/{}: {}", fileCount, files.size(), file.getFileName());
                processFile(file.toString());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("Directory complete: {} files, {} sentences, {} tokens in {}s",
            fileCount, sentenceId.get(), tokenCount.get(), elapsed / 1000);
    }

    /**
     * Build a SentenceDocument from parsed CoNLL-U tokens.
     */
    private SentenceDocument buildSentence(List<String[]> tokenLines, String text) {
        int id = sentenceId.incrementAndGet();
        
        SentenceDocument.Builder builder = SentenceDocument.builder()
            .sentenceId(id)
            .text(text);

        int position = 0;
        int currentOffset = 0;
        
        for (String[] fields : tokenLines) {
            try {
                // Skip multi-word tokens (e.g., "1-2")
                if (fields[0].contains("-") || fields[0].contains(".")) {
                    continue;
                }

                String word = fields[1];
                String lemma = fields.length > 2 ? fields[2] : word;
                String upos = fields.length > 3 ? fields[3] : "X";
                String xpos = fields.length > 4 ? fields[4] : upos;

                // Use XPOS if available, otherwise UPOS
                String tag = (xpos != null && !xpos.equals("_")) ? xpos : upos;
                
                // Fix null/underscore values
                if (lemma == null || lemma.equals("_")) lemma = word;
                if (tag == null || tag.equals("_")) tag = "X";

                // Calculate approximate offsets from text
                int startOffset = text.indexOf(word, currentOffset);
                if (startOffset < 0) startOffset = currentOffset;
                int endOffset = startOffset + word.length();
                currentOffset = endOffset;

                builder.addToken(position, word, lemma, tag, startOffset, endOffset);
                position++;
                
            } catch (NumberFormatException e) {
                // Skip invalid token IDs
            }
        }

        return builder.build();
    }

    /**
     * Write statistics file.
     */
    public void writeStatistics(String statsPath) throws IOException {
        indexer.writeStatistics(statsPath);
    }

    /**
     * Get total sentences indexed.
     */
    public int getSentenceCount() {
        return sentenceId.get();
    }

    /**
     * Get total tokens indexed.
     */
    public long getTokenCount() {
        return tokenCount.get();
    }

    @Override
    public void close() throws IOException {
        indexer.commit();
        indexer.close();
        logger.info("HybridConllUProcessor closed: {} sentences, {} tokens",
            sentenceId.get(), tokenCount.get());
    }

    /**
     * CLI entry point for building hybrid index.
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: HybridConllUProcessor <input> <outputIndex> [--stats <statsFile>]");
            System.err.println();
            System.err.println("Arguments:");
            System.err.println("  <input>       Path to CoNLL-U file or directory");
            System.err.println("  <outputIndex> Path for output hybrid index");
            System.err.println("  --stats       Optional path for statistics file");
            System.err.println();
            System.err.println("Examples:");
            System.err.println("  HybridConllUProcessor corpus.conllu index/");
            System.err.println("  HybridConllUProcessor corpus_dir/ index/ --stats stats.tsv");
            System.exit(1);
        }

        String input = args[0];
        String outputIndex = args[1];
        String statsPath = outputIndex + "/stats.tsv";

        // Parse options
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--stats") && i + 1 < args.length) {
                statsPath = args[++i];
            }
        }

        System.out.println("=== Hybrid Index Builder ===");
        System.out.println("Input: " + input);
        System.out.println("Output: " + outputIndex);
        System.out.println("Stats: " + statsPath);
        System.out.println();

        long startTime = System.currentTimeMillis();

        try (HybridConllUProcessor processor = new HybridConllUProcessor(outputIndex)) {
            Path inputPath = Paths.get(input);
            
            if (Files.isDirectory(inputPath)) {
                processor.processDirectory(input, "*.conllu");
            } else {
                processor.processFile(input);
            }

            processor.writeStatistics(statsPath);

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println();
            System.out.println("=== Complete ===");
            System.out.println("Sentences: " + processor.getSentenceCount());
            System.out.println("Tokens: " + processor.getTokenCount());
            System.out.println("Time: " + (elapsed / 1000) + "s");
            System.out.println("Rate: " + String.format("%.0f", processor.getTokenCount() / (elapsed / 1000.0)) + " tokens/s");
        }
    }
}

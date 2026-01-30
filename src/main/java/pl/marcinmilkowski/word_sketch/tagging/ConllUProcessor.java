package pl.marcinmilkowski.word_sketch.tagging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.indexer.LuceneIndexer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Processor for pre-tagged corpora in CoNLL-U format.
 *
 * This allows using external POS taggers (UDPipe, Stanza, spaCy) to tag
 * the corpus, then index the results here.
 *
 * CoNLL-U format:
 * # comment lines (optional)
 * 1   word    lemma   UPOS    XPOS    feats   head    deprel   deps   misc
 * ...
 *
 * Example:
 * # sent_id = 1
 * # text = The cat sat on the mat.
 * 1   The     the     DET     DT      _       2       det      _       _
 * 2   cat     cat     NOUN    NN      _       3       nsubj    _       _
 * 3   sat     sit     VERB    VBD     _       0       root     _       _
 * 4   on      on      ADP     IN      _       6       case     _       _
 * 5   the     the     DET     DT      _       6       det      _       _
 * 6   mat     mat     NOUN    NN      _       3       obl      _       _
 * 7   .       .       PUNCT   .       _       3       punct    _       _
 */
public class ConllUProcessor {

    private static final Logger logger = LoggerFactory.getLogger(ConllUProcessor.class);

    private final LuceneIndexer indexer;

    public ConllUProcessor(LuceneIndexer indexer) {
        this.indexer = indexer;
    }

    /**
     * Process a CoNLL-U file and index it.
     *
     * @param inputFile Path to the CoNLL-U input file
     */
    public void processFile(String inputFile) throws IOException {
        processFile(inputFile, 10000);
    }

    /**
     * Process a CoNLL-U file with batch commits.
     *
     * @param inputFile Path to the CoNLL-U input file
     * @param commitInterval Commit after every N sentences
     */
    public void processFile(String inputFile, int commitInterval) throws IOException {
        logger.info("Processing CoNLL-U file: " + inputFile);

        Path path = Paths.get(inputFile);
        int sentenceId = 0;
        int tokenCount = 0;
        int sentenceCount = 0;
        long startTime = System.currentTimeMillis();

        List<String> currentSentenceLines = new ArrayList<>();
        String currentText = "";
        int position = 0;

        // Use lenient UTF-8 decoder that replaces invalid bytes instead of throwing
        java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
            .replaceWith("?");
        BufferedReader reader = new BufferedReader(new InputStreamReader(
            Files.newInputStream(path), decoder));

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#")) {
                    // End of sentence
                    if (!currentSentenceLines.isEmpty()) {
                        processCoNLLUSentence(currentSentenceLines, currentText, sentenceId);
                        sentenceId++;
                        sentenceCount++;
                        tokenCount += currentSentenceLines.size();

                        if (sentenceCount % commitInterval == 0) {
                            indexer.commit();
                            long elapsed = System.currentTimeMillis() - startTime;
                            double tokensPerSec = tokenCount / (elapsed / 1000.0);
                            logger.info("Processed " + sentenceCount + " sentences, " +
                                       tokenCount + " tokens (" + String.format("%.1f", tokensPerSec) + " tokens/sec)");
                        }

                        currentSentenceLines.clear();
                        currentText = "";
                        position = 0;
                    }

                    // Extract text from comment
                    if (line.startsWith("# text =")) {
                        currentText = line.substring("# text =".length()).trim();
                    }
                } else {
                    // Token line
                    currentSentenceLines.add(line);
                }
            }

            // Process remaining sentence
            if (!currentSentenceLines.isEmpty()) {
                processCoNLLUSentence(currentSentenceLines, currentText, sentenceId);
                sentenceCount++;
            }
        } finally {
            reader.close();
        }

        // Final commit for this file
        indexer.commit();

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("CoNLL-U processing complete. Processed " + sentenceCount +
                   " sentences, " + tokenCount + " tokens in " + (elapsed / 1000) + "s");
    }

    /**
     * Close the processor and finalize the index.
     * Performs final commit and optimization.
     */
    public void close() throws IOException {
        indexer.commit();
        indexer.optimize();
        indexer.close();
    }

    /**
     * Process a list of CoNLL-U token lines into a sentence.
     */
    private void processCoNLLUSentence(List<String> lines, String sentenceText, int sentenceId) throws IOException {
        int position = 0;
        StringBuilder sentenceBuilder = new StringBuilder();
        boolean firstWord = true;

        for (String line : lines) {
            String[] fields = line.split("\t");
            if (fields.length < 10) {
                logger.warn("Invalid CoNLL-U line: " + line);
                continue;
            }

            try {
                int tokenId = Integer.parseInt(fields[0]);
                String word = fields[1];
                String lemma = fields[2];
                String upos = fields[3];
                String xpos = fields[4];
                String feats = fields[5];

                // Use XPOS if available, otherwise UPOS
                String tag = (xpos != null && !xpos.equals("_")) ? xpos : upos;

                // Determine pos_group from UPOS
                String posGroup = mapUPOStoGroup(upos);

                // Calculate offsets
                if (firstWord) {
                    sentenceBuilder.append(word);
                    firstWord = false;
                } else {
                    sentenceBuilder.append(" ").append(word);
                }

                int startOffset = sentenceBuilder.length() - word.length();
                int endOffset = sentenceBuilder.length();

                // Index the word
                indexer.addWord(
                    sentenceId,
                    position,
                    word,
                    lemma != null && !lemma.equals("_") ? lemma : word,
                    tag != null && !tag.equals("_") ? tag : "X",
                    posGroup,
                    sentenceBuilder.toString(),
                    startOffset,
                    endOffset
                );

                position++;
            } catch (NumberFormatException e) {
                // Skip multi-word tokens (e.g., "1-2") or empty lines
                logger.debug("Skipping non-numeric token ID: " + fields[0]);
            }
        }
    }

    /**
     * Map Universal POS tag to broad category.
     */
    private String mapUPOStoGroup(String upos) {
        if (upos == null || upos.equals("_")) {
            return "x";
        }
        switch (upos.toUpperCase()) {
            case "NOUN":
            case "PROPN":
            case "PRON":
                return "noun";
            case "VERB":
                return "verb";
            case "ADJ":
                return "adj";
            case "ADV":
                return "adv";
            case "ADP":
            case "PREP":
            case "POST":
                return "prep";
            case "DET":
                return "det";
            case "CONJ":
                return "conj";
            case "PUNCT":
                return "punct";
            case "NUM":
                return "num";
            case "AUX":
                return "verb";
            case "CCONJ":
            case "SCONJ":
                return "conj";
            case "INTJ":
                return "intj";
            case "PART":
                return "part";
            default:
                return "x";
        }
    }

    /**
     * Factory method to create a CoNLL-U processor.
     */
    public static ConllUProcessor create(String indexPath) throws IOException {
        return create(indexPath, 1); // Default to 1 thread
    }

    /**
     * Factory method to create a CoNLL-U processor with specified threads.
     */
    public static ConllUProcessor create(String indexPath, int numThreads) throws IOException {
        LuceneIndexer indexer = new LuceneIndexer(indexPath, numThreads);
        return new ConllUProcessor(indexer);
    }

    /**
     * Example method to convert a tagged file from another format.
     * Takes a simple word/lemma/tag file and converts to CoNLL-U.
     */
    public static void convertSimpleFormat(String inputFile, String outputFile) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(inputFile));
             BufferedWriter writer = Files.newBufferedWriter(Paths.get(outputFile))) {

            int sentId = 1;
            int tokenId = 1;
            StringBuilder sentence = new StringBuilder();

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    // End of sentence - write sent_id and text comments
                    if (sentence.length() > 0) {
                        writer.write("# sent_id = " + sentId + "\n");
                        writer.write("# text = " + sentence.toString() + "\n");
                        sentId++;
                        sentence.setLength(0);
                        tokenId = 1;
                    }
                } else {
                    // Assume format: word lemma tag
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        String word = parts[0];
                        String lemma = parts[1];
                        String tag = parts[2];

                        // Convert tag to XPOS-like format
                        String xpos = guessXPOS(tag);
                        String upos = guessUPOS(tag);

                        writer.write(String.format("%d\t%s\t%s\t%s\t%s\t_\t_\t_\t_\t_\n",
                            tokenId, word, lemma, upos, xpos));
                        tokenId++;

                        if (sentence.length() > 0) {
                            sentence.append(" ");
                        }
                        sentence.append(word);
                    }
                }
            }
        }
        System.out.println("Converted " + inputFile + " to CoNLL-U format: " + outputFile);
    }

    /**
     * Guess Universal POS tag from Penn Treebank tag.
     */
    private static String guessUPOS(String tag) {
        if (tag == null) return "X";
        tag = tag.toUpperCase();
        if (tag.startsWith("N")) return "NOUN";
        if (tag.startsWith("V")) return "VERB";
        if (tag.startsWith("J")) return "ADJ";
        if (tag.startsWith("R")) return "ADV";
        if (tag.startsWith("D")) return "DET";
        if (tag.startsWith("P")) {
            if (tag.equals("PP$") || tag.startsWith("PR")) return "PRON";
            return "PROPN";
        }
        if (tag.startsWith("C")) return "CCONJ";
        if (tag.startsWith("I")) return "ADP";
        if (tag.startsWith("M")) return "NUM";
        if (tag.equals("TO")) return "PART";
        if (tag.equals("IN")) return "ADP";
        if (tag.equals("DT") || tag.equals("WD") || tag.equals("WDT")) return "DET";
        return "X";
    }

    /**
     * Normalize tag to XPOS format.
     */
    private static String guessXPOS(String tag) {
        return tag != null ? tag.toUpperCase() : "X";
    }
}

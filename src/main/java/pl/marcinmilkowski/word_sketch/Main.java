package pl.marcinmilkowski.word_sketch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.api.WordSketchApiServer;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader;
import pl.marcinmilkowski.word_sketch.indexer.blacklab.BlackLabConllUIndexer;
import pl.marcinmilkowski.word_sketch.query.BlackLabQueryExecutor;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

import nl.inl.blacklab.index.DocumentFormats;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Main entry point for ConceptSketch using BlackLab backend.
 *
 * BlackLab provides native CoNLL-U dependency indexing and CQL query support.
 *
 * Commands:
 *   blacklab-index --input corpus.conllu --output data/index/
 *   blacklab-query --index data/index/ --lemma theory --deprel amod
 *   server --index data/index/ --port 8080
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        System.out.println("==========================================");
        System.out.println("   ConceptSketch - BlackLab Edition v1.5.0 ");
        System.out.println("==========================================");
        System.out.println();

        if (args.length == 0) {
            showUsage();
            return;
        }

        try {
            String command = args[0].toLowerCase();

            switch (command) {
                case "blacklab-index":
                    handleBlackLabIndexCommand(args);
                    break;
                case "blacklab-query":
                    handleBlackLabQueryCommand(args);
                    break;
                case "server":
                    handleServerCommand(args);
                    break;
                case "help":
                    showUsage();
                    break;
                default:
                    logger.error("Unknown command: " + command);
                    showUsage();
            }
        } catch (Exception e) {
            logger.error("Application error", e);
            System.err.println("Error: " + e.getMessage());
            System.err.println("Use 'help' command for usage information.");
        }
    }

    private static void showUsage() {
        System.out.println("Usage: java -jar concept-sketch.jar <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  blacklab-index --input <file.conllu> --output <index-dir>");
        System.out.println("      Index a CoNLL-U file with BlackLab");
        System.out.println();
        System.out.println("  blacklab-query --index <dir> --lemma <word> [--deprel <rel>]");
        System.out.println("      Query the index for collocations");
        System.out.println("      Options:");
        System.out.println("        --deprel <rel>   Dependency relation (e.g., amod, nsubj, obj)");
        System.out.println("        --min-logdice <n>  Minimum logDice score (default: 0)");
        System.out.println("        --limit <n>      Max results (default: 20)");
        System.out.println();
        System.out.println("  server --index <dir> [--port <port>]");
        System.out.println("      Start REST API server");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Tag corpus with Stanza (Python required)");
        System.out.println("  python tag_with_stanza.py -i corpus.txt -o corpus.conllu");
        System.out.println();
        System.out.println("  # Index CoNLL-U file");
        System.out.println("  java -jar concept-sketch.jar blacklab-index \\");
        System.out.println("    --input corpus.conllu --output data/index/");
        System.out.println();
        System.out.println("  # Query for adjectival modifiers of 'theory'");
        System.out.println("  java -jar concept-sketch.jar blacklab-query \\");
        System.out.println("    --index data/index/ --lemma theory --deprel amod");
        System.out.println();
        System.out.println("  # Start API server");
        System.out.println("  java -jar concept-sketch.jar server \\");
        System.out.println("    --index data/index/ --port 8080");
        System.out.println();
        System.out.println("  # Query API");
        System.out.println("  curl 'http://localhost:8080/api/sketch/theory?deprel=amod'");
        System.out.println();
    }

    private static final Pattern MWT_OR_EMPTY = Pattern.compile("^\\d+-\\d+\t|^\\d+\\.\\d+\t");

    private static void handleBlackLabIndexCommand(String[] args) throws IOException {
        String inputPath = null;
        String outputPath = null;
        String formatDir = ".";

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--input": case "-i": inputPath = args[++i]; break;
                case "--output": case "-o": outputPath = args[++i]; break;
                case "--format-dir": formatDir = args[++i]; break;
                default: System.err.println("Unknown option: " + args[i]);
            }
        }

        if (inputPath == null || outputPath == null) {
            System.err.println("Error: --input and --output are required");
            System.err.println("Usage: blacklab-index --input corpus.conllu --output index-dir/ [--format-dir .]");
            return;
        }

        System.out.println("=== BlackLab Indexer ===");
        System.out.println("Input:      " + inputPath);
        System.out.println("Output:     " + outputPath);
        System.out.println("Format dir: " + formatDir);
        System.out.println();

        // Step 1: Convert CoNLL-U → WPL chunk files (BlackLab indexes one file = one document)
        System.out.println("Step 1/3: Converting CoNLL-U → WPL chunks (10 000 sentences/file)...");
        Path wplTempDir = Files.createTempDirectory("conllu_wpl_");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(wplTempDir)));
        long[] counts = convertConlluToWplChunks(Paths.get(inputPath), wplTempDir, 10_000);
        System.out.printf("  → %,d sentences, %,d tokens in %,d chunk files%n%n",
                counts[0], counts[1], counts[2]);

        // Step 2: Register format(s) from the format directory
        System.out.println("Step 2/3: Registering format 'conllu-sentences' from " + formatDir + "...");
        DocumentFormats.addConfigFormatsInDirectories(List.of(new File(formatDir)));
        System.out.println("  → Done");
        System.out.println();

        // Step 3: Index the chunk directory with BlackLab
        System.out.println("Step 3/3: Indexing with BlackLab...");
        try (BlackLabConllUIndexer indexer = new BlackLabConllUIndexer(outputPath, "conllu-sentences")) {
            indexer.indexFile(wplTempDir.toString());
        }
    }

    /**
     * Converts CoNLL-U to tabular WPL with &lt;s&gt; markers, split into chunk files.
     * BlackLab's tabular indexer loads each file fully into memory, so large corpora
     * must be split into manageable chunks (one file = one BlackLab document).
     * Returns [sentenceCount, tokenCount, chunkCount].
     */
    private static long[] convertConlluToWplChunks(Path input, Path outputDir, int sentencesPerChunk)
            throws IOException {
        long sentences = 0, tokens = 0, chunks = 0;
        boolean inSentence = false;
        int sentencesInChunk = 0;
        BufferedWriter w = null;

        try (BufferedReader r = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) continue;
                if (line.isBlank()) {
                    if (inSentence) {
                        w.write("</s>\n");
                        sentences++;
                        sentencesInChunk++;
                        inSentence = false;
                        if (sentencesInChunk >= sentencesPerChunk) {
                            w.close(); w = null; chunks++; sentencesInChunk = 0;
                        }
                    }
                    continue;
                }
                if (MWT_OR_EMPTY.matcher(line).find()) continue;
                if (!inSentence) {
                    if (w == null) {
                        Path chunk = outputDir.resolve(String.format("chunk_%06d.tsv", chunks));
                        w = Files.newBufferedWriter(chunk, StandardCharsets.UTF_8);
                    }
                    w.write("<s>\n");
                    inSentence = true;
                }
                w.write(line); w.write('\n');
                tokens++;
            }
            if (inSentence && w != null) { w.write("</s>\n"); sentences++; }
        } finally {
            if (w != null) { w.close(); chunks++; }
        }
        return new long[]{sentences, tokens, chunks};
    }

    private static void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .map(Path::toFile)
                  .forEach(java.io.File::delete);
        } catch (IOException ignored) {}
    }

    private static void handleBlackLabQueryCommand(String[] args) throws IOException {
        String indexPath = null;
        String lemma = null;
        String deprel = null;
        double minLogDice = 0;
        int limit = 20;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--index":
                case "-i":
                    indexPath = args[++i];
                    break;
                case "--lemma":
                case "-w":
                    lemma = args[++i];
                    break;
                case "--deprel":
                    deprel = args[++i];
                    break;
                case "--min-logdice":
                    minLogDice = Double.parseDouble(args[++i]);
                    break;
                case "--limit":
                    limit = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
            }
        }

        if (indexPath == null || lemma == null) {
            System.err.println("Error: --index and --lemma are required");
            return;
        }

        System.out.println("=== BlackLab Query ===");
        System.out.println("Index: " + indexPath);
        System.out.println("Lemma: " + lemma);
        if (deprel != null) {
            System.out.println("Dependency: " + deprel);
        }
        System.out.println("Min logDice: " + minLogDice);
        System.out.println("Limit: " + limit);
        System.out.println();

        try (BlackLabQueryExecutor executor = new BlackLabQueryExecutor(indexPath)) {
            var results = deprel != null
                ? executor.findDependencyCollocations(lemma, deprel, minLogDice, limit)
                : executor.findCollocations(lemma, "[]", minLogDice, limit);

            if (results.isEmpty()) {
                System.out.println("No results found.");
                return;
            }

            System.out.println("Results:");
            System.out.println("--------");
            for (var result : results) {
                System.out.printf("  %s: freq=%d, logDice=%.2f, relFreq=%.4f%n",
                    result.getLemma(),
                    result.getFrequency(),
                    result.getLogDice(),
                    result.getRelativeFrequency()
                );
            }
        }
    }

    private static void handleServerCommand(String[] args) throws IOException {
        String indexPath = null;
        int port = 8080;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--index":
                case "-i":
                    indexPath = args[++i];
                    break;
                case "--port":
                case "-p":
                    port = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
            }
        }

        if (indexPath == null) {
            System.err.println("Error: --index is required");
            System.err.println("Usage: java -jar concept-sketch.jar server --index <dir> [--port <port>]");
            return;
        }

        System.out.println("Starting API server...");
        System.out.println("Index: " + indexPath);
        System.out.println("Port: " + port);
        System.out.println();

        QueryExecutor executor = new BlackLabQueryExecutor(indexPath);

        // Load grammar configuration (optional)
        GrammarConfigLoader grammarConfig = null;
        try {
            var grammarPath = java.nio.file.Paths.get("grammars/relations.json");
            grammarConfig = new GrammarConfigLoader(grammarPath);
            System.out.println("Loaded grammar config: " + grammarConfig.getVersion());
        } catch (IOException e) {
            System.out.println("No grammar config found, using defaults.");
        }

        WordSketchApiServer server = WordSketchApiServer.builder()
            .withExecutor(executor)
            .withIndexPath(indexPath)
            .withPort(port)
            .withGrammarConfig(grammarConfig)
            .build();

        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            server.stop();
            try {
                executor.close();
            } catch (IOException e) {
                // Ignore
            }
        }));

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

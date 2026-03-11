package pl.marcinmilkowski.word_sketch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.api.WordSketchApiServer;
import pl.marcinmilkowski.word_sketch.config.GrammarConfig;
import pl.marcinmilkowski.word_sketch.config.GrammarConfigLoader;
import pl.marcinmilkowski.word_sketch.indexer.blacklab.BlackLabConllUIndexer;
import pl.marcinmilkowski.word_sketch.indexer.blacklab.ConlluConverter;
import pl.marcinmilkowski.word_sketch.query.BlackLabQueryExecutor;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;

import nl.inl.blacklab.index.DocumentFormats;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
                    handleBlackLabServerCommand(args);
                    break;
                case "help":
                    showUsage();
                    break;
                default:
                    logger.error("Unknown command: {}", command);
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

    private static void handleBlackLabIndexCommand(String[] args) throws IOException {
        String inputPath = null;
        String outputPath = null;
        String formatDir = ".";

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--input": case "-i": inputPath = requireNextArg(args, ++i, "--input"); break;
                case "--output": case "-o": outputPath = requireNextArg(args, ++i, "--output"); break;
                case "--format-dir": formatDir = requireNextArg(args, ++i, "--format-dir"); break;
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
        long[] counts = ConlluConverter.convertConlluToWplChunks(Paths.get(inputPath), wplTempDir, 10_000);
        long sentenceCount = counts[0];
        long tokenCount = counts[1];
        long chunkFileCount = counts[2];
        System.out.printf("  → %,d sentences, %,d tokens in %,d chunk files%n%n",
                sentenceCount, tokenCount, chunkFileCount);

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

    private static void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .map(Path::toFile)
                  .forEach(java.io.File::delete);
        } catch (IOException e) {
            logger.warn("Failed to delete temp directory {}: {}", dir, e.getMessage());
        }
    }

    private static void handleBlackLabQueryCommand(String[] args) throws IOException {
        String indexPath = null;
        String lemma = null;
        String deprel = null;
        double minLogDice = 0;
        int limit = 20;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--index": case "-i": indexPath = requireNextArg(args, ++i, "--index"); break;
                case "--lemma": case "-w": lemma = requireNextArg(args, ++i, "--lemma"); break;
                case "--deprel": deprel = requireNextArg(args, ++i, "--deprel"); break;
                case "--min-logdice": minLogDice = Double.parseDouble(requireNextArg(args, ++i, "--min-logdice")); break;
                case "--limit": limit = Integer.parseInt(requireNextArg(args, ++i, "--limit")); break;
                default: System.err.println("Unknown option: " + args[i]);
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
                ? executor.executeDependencyPattern(lemma, deprel, minLogDice, limit)
                : executor.findCollocations(lemma, "[]", minLogDice, limit);

            if (results.isEmpty()) {
                System.out.println("No results found.");
                return;
            }

            System.out.println("Results:");
            System.out.println("--------");
            for (var result : results) {
                System.out.printf("  %s: freq=%d, logDice=%.2f, relFreq=%.4f%n",
                    result.lemma(),
                    result.frequency(),
                    result.logDice(),
                    result.relativeFrequency()
                );
            }
        }
    }

    private static void handleBlackLabServerCommand(String[] args) throws IOException {
        String indexPath = null;
        int port = 8080;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--index": case "-i": indexPath = requireNextArg(args, ++i, "--index"); break;
                case "--port": case "-p": port = Integer.parseInt(requireNextArg(args, ++i, "--port")); break;
                default: System.err.println("Unknown option: " + args[i]);
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

        // Load grammar configuration (required)
        // Override path via system property: -Dgrammar.config=path/to/relations.json
        String grammarConfigPath = System.getProperty("grammar.config", "grammars/relations.json");
        GrammarConfig grammarConfig;
        try {
            var grammarPath = java.nio.file.Paths.get(grammarConfigPath);
            grammarConfig = GrammarConfigLoader.load(grammarPath);
            System.out.println("Loaded grammar config: " + grammarConfig.getVersion());
        } catch (IOException e) {
            logger.error("Failed to load grammar config at '{}': {}", grammarConfigPath, e.getMessage());
            System.err.println("Error: failed to load grammar config at '" + grammarConfigPath + "': " + e.getMessage());
            System.err.println("Ensure the file exists and is valid JSON, or set -Dgrammar.config=<path>.");
            executor.close();
            return;
        }

        // Register the shutdown hook before constructing the server so the executor is
        // always closed even if server construction throws.
        AtomicReference<WordSketchApiServer> serverHolder = new AtomicReference<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            WordSketchApiServer s = serverHolder.get();
            if (s != null) s.stop();
            try {
                executor.close();
            } catch (IOException e) {
                System.err.println("Warning: failed to close index during shutdown: " + e.getMessage());
            }
        }));

        WordSketchApiServer server = new WordSketchApiServer(executor, port, grammarConfig);
        serverHolder.set(server);

        server.start();

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the next argument from {@code args} at index {@code i}, or throws
     * {@link IllegalArgumentException} if the index is out of bounds.
     *
     * <p>Callers in {@code main} let this propagate to the top-level {@code catch (Exception e)}
     * handler which prints the message and exits cleanly.</p>
     */
    private static String requireNextArg(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " requires an argument");
        }
        return args[i];
    }
}
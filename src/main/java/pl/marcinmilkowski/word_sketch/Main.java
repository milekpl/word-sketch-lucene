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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        System.out.print("""
            Usage: java -jar concept-sketch.jar <command> [options]

            Commands:
              blacklab-index --input <file.conllu> --output <index-dir>
                  Index a CoNLL-U file with BlackLab

              blacklab-query --index <dir> --lemma <word> [--deprel <rel>]
                  Query the index for collocations
                  Options:
                    --deprel <rel>   Dependency relation (e.g., amod, nsubj, obj)
                    --min-logdice <n>  Minimum logDice score (default: 0)
                    --limit <n>      Max results (default: 20)

              server --index <dir> [--port <port>]
                  Start REST API server

            Examples:
              # Tag corpus with Stanza (Python required)
              python tag_with_stanza.py -i corpus.txt -o corpus.conllu

              # Index CoNLL-U file
              java -jar concept-sketch.jar blacklab-index \\
                --input corpus.conllu --output data/index/

              # Query for adjectival modifiers of 'theory'
              java -jar concept-sketch.jar blacklab-query \\
                --index data/index/ --lemma theory --deprel amod

              # Start API server
              java -jar concept-sketch.jar server \\
                --index data/index/ --port 8080

              # Query API
              curl 'http://localhost:8080/api/sketch/theory?deprel=amod'

            """);
    }

    private static void handleBlackLabIndexCommand(String[] args) throws IOException {
        Map<String, String> params = parseCommandArguments(args, "blacklab-index");
        String inputPath = getParam(params, "--input", "-i");
        String outputPath = getParam(params, "--output", "-o");
        String formatDir = getParam(params, "--format-dir");
        if (formatDir == null) formatDir = ".";

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
        ConlluConverter.ConversionStats stats = ConlluConverter.convertConlluToWplChunks(Paths.get(inputPath), wplTempDir, 10_000);
        long sentenceCount = stats.sentences();
        long tokenCount = stats.tokens();
        long chunkFileCount = stats.chunks();
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
        Map<String, String> params = parseCommandArguments(args, "blacklab-query");
        String indexPath = getParam(params, "--index", "-i");
        String lemma = getParam(params, "--lemma", "-w");
        String deprel = getParam(params, "--deprel");
        double minLogDice = parseDoubleParam(params, "--min-logdice", 0.0);
        int limit = parseIntParam(params, "--limit", 20);

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

        try (QueryExecutor executor = new BlackLabQueryExecutor(indexPath)) {
            var results = deprel != null
                ? executor.executeDependencyPattern(lemma, deprel, minLogDice, limit, null)
                : executor.executeCollocations(lemma, "[]", minLogDice, limit);

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
        Map<String, String> params = parseCommandArguments(args, "server");
        String indexPath = getParam(params, "--index", "-i");
        String portVal = getParam(params, "--port", "-p");
        int port = 8080;
        if (portVal != null) {
            try {
                port = Integer.parseInt(portVal);
            } catch (NumberFormatException e) {
                System.err.println("Invalid value for --port: '" + portVal + "' is not a valid integer.");
                System.exit(1);
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
            System.out.println("Loaded grammar config: " + grammarConfig.version());
        } catch (IllegalArgumentException e) {
            logger.error("Invalid grammar config at '{}': {}", grammarConfigPath, e.getMessage());
            System.err.println("Error: invalid grammar config at '" + grammarConfigPath + "': " + e.getMessage());
            System.err.println("Check the JSON for missing fields, duplicate IDs, or invalid patterns.");
            executor.close();
            return;
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
     * Parses {@code args[1..]} into a flag→value map. Consecutive pairs of {@code --flag value}
     * are stored as-is; a trailing flag with no value emits a warning and is skipped.
     */
    private static Map<String, String> parseCommandArguments(String[] args, String commandName) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 < args.length) {
                params.put(flag, args[++i]);
            } else {
                System.err.println("[" + commandName + "] Option '" + flag + "' requires a value but none was provided.");
            }
        }
        return params;
    }

    /** Returns the first non-null value for any of the given keys, or {@code null}. */
    private static String getParam(Map<String, String> params, String... keys) {
        for (String key : keys) {
            if (params.containsKey(key)) return params.get(key);
        }
        return null;
    }

    /** Parses an integer parameter from the map, returning {@code defaultValue} if absent. */
    private static int parseIntParam(Map<String, String> params, String option, int defaultValue) {
        String val = params.get(option);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            System.err.println("Invalid value for " + option + ": '" + val + "' is not a valid integer.");
            System.exit(1);
            throw new AssertionError("unreachable");
        }
    }

    /** Parses a double parameter from the map, returning {@code defaultValue} if absent. */
    private static double parseDoubleParam(Map<String, String> params, String option, double defaultValue) {
        String val = params.get(option);
        if (val == null) return defaultValue;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            System.err.println("Invalid value for " + option + ": '" + val + "' is not a valid number.");
            System.exit(1);
            throw new AssertionError("unreachable");
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
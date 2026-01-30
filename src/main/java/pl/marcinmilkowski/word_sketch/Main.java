package pl.marcinmilkowski.word_sketch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.api.WordSketchApiServer;
import pl.marcinmilkowski.word_sketch.query.SnowballCollocations;
import pl.marcinmilkowski.word_sketch.query.WordSketchQueryExecutor;
import pl.marcinmilkowski.word_sketch.tagging.ConllUProcessor;
import pl.marcinmilkowski.word_sketch.tagging.CorpusProcessor;
import pl.marcinmilkowski.word_sketch.tagging.SimpleTagger;
import pl.marcinmilkowski.word_sketch.tagging.UDPipeTagger;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Main entry point for the Word Sketch Lucene application.
 * This class orchestrates the initialization and execution of the word sketch system.
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        logger.info("Starting Word Sketch Lucene application...");

        // Display welcome message
        System.out.println("==========================================");
        System.out.println("        Word Sketch Lucene v1.0.0         ");
        System.out.println("==========================================");
        System.out.println();

        // Parse command-line arguments
        if (args.length == 0) {
            showUsage();
            return;
        }

        try {
            String command = args[0].toLowerCase();

            switch (command) {
                case "index":
                    handleIndexCommand(args);
                    break;
                case "query":
                    handleQueryCommand(args);
                    break;
                case "server":
                    handleServerCommand(args);
                    break;
                case "tag":
                    handleTagCommand(args);
                    break;
                case "conllu":
                    handleConllUCommand(args);
                    break;
                case "convert":
                    handleConvertCommand(args);
                    break;
                case "snowball":
                    handleSnowballCommand(args);
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

    private static void handleIndexCommand(String[] args) throws IOException {
        String corpusFile = null;
        String indexPath = null;
        String language = "english";
        int batchSize = 1000;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--corpus":
                case "-c":
                    corpusFile = args[++i];
                    break;
                case "--output":
                case "-o":
                    indexPath = args[++i];
                    break;
                case "--language":
                case "-l":
                    language = args[++i];
                    break;
                case "--batch":
                    batchSize = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
            }
        }

        if (corpusFile == null || indexPath == null) {
            System.err.println("Error: --corpus and --output are required");
            System.err.println("Usage: java -jar word-sketch-lucene.jar index --corpus <file> --output <path>");
            return;
        }

        System.out.println("Indexing corpus: " + corpusFile);
        System.out.println("Output index: " + indexPath);
        System.out.println("Language: " + language);
        System.out.println();

        CorpusProcessor processor = CorpusProcessor.create(indexPath, language);
        processor.processCorpus(corpusFile, indexPath, batchSize);

        System.out.println("Indexing complete!");
    }

    private static void handleQueryCommand(String[] args) throws IOException {
        String indexPath = null;
        String lemma = null;
        String cqlPattern = null;
        double minLogDice = 0;
        int limit = 50;

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
                case "--pattern":
                case "-p":
                    cqlPattern = args[++i];
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

        if (indexPath == null) {
            System.err.println("Error: --index is required");
            System.err.println("Usage: java -jar word-sketch-lucene.jar query --index <path> --lemma <word>");
            return;
        }

        if (lemma == null) {
            System.err.println("Error: --lemma is required");
            System.err.println("Usage: java -jar word-sketch-lucene.jar query --index <path> --lemma <word>");
            return;
        }

        if (cqlPattern == null) {
            // Default pattern: find nouns modified by the lemma
            cqlPattern = "1:NOUN [tag=\"JJ\"|tag=\"RB\"] 2:" + lemma;
        }

        System.out.println("Querying index: " + indexPath);
        System.out.println("Lemma: " + lemma);
        System.out.println("Pattern: " + cqlPattern);
        System.out.println("Min logDice: " + minLogDice);
        System.out.println("Limit: " + limit);
        System.out.println();

        WordSketchQueryExecutor executor = new WordSketchQueryExecutor(indexPath);
        var results = executor.findCollocations(lemma, cqlPattern, minLogDice, limit);

        System.out.println("Results:");
        System.out.println("--------");
        for (var result : results) {
            System.out.printf("  %s (%s): freq=%d, logDice=%.2f, relFreq=%.4f%n",
                result.getLemma(),
                result.getPos(),
                result.getFrequency(),
                result.getLogDice(),
                result.getRelativeFrequency()
            );
        }

        executor.close();
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
            System.err.println("Usage: java -jar word-sketch-lucene.jar server --index <path> [--port <port>]");
            return;
        }

        System.out.println("Starting API server...");
        System.out.println("Index: " + indexPath);
        System.out.println("Port: " + port);
        System.out.println();
        System.out.println("Endpoints:");
        System.out.println("  GET  /health - Health check");
        System.out.println("  GET  /sketch/{lemma} - Get word sketch");
        System.out.println("  POST /sketch/query - Custom CQL query");
        System.out.println();
        System.out.println("Press Ctrl+C to stop the server.");
        System.out.println();

        WordSketchQueryExecutor executor = new WordSketchQueryExecutor(indexPath);
        WordSketchApiServer server = WordSketchApiServer.builder()
            .withExecutor(executor)
            .withPort(port)
            .build();

        server.start();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            server.stop();
            try {
                executor.close();
            } catch (IOException e) {
                // Ignore
            }
        }));

        // Keep running until interrupted
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void handleTagCommand(String[] args) throws IOException {
        String inputFile = null;
        String outputFile = null;
        String language = "english";

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--input":
                case "-i":
                    inputFile = args[++i];
                    break;
                case "--output":
                case "-o":
                    outputFile = args[++i];
                    break;
                case "--language":
                case "-l":
                    language = args[++i];
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
            }
        }

        if (inputFile == null || outputFile == null) {
            System.err.println("Error: --input and --output are required");
            System.err.println("Usage: java -jar word-sketch-lucene.jar tag --input <file> --output <file>");
            return;
        }

        System.out.println("Tagging corpus: " + inputFile);
        System.out.println("Output: " + outputFile);
        System.out.println("Language: " + language);
        System.out.println();

        // Try UDPipe first, fall back to simple tagger
        pl.marcinmilkowski.word_sketch.tagging.PosTagger tagger;
        try {
            tagger = UDPipeTagger.createForLanguage(language);
            System.out.println("Using UDPipe tagger");
        } catch (IOException e) {
            System.out.println("UDPipe not available, using simple tagger");
            tagger = SimpleTagger.create();
        }

        // Read input and tag
        java.nio.file.Path inPath = Paths.get(inputFile);
        java.nio.file.Path outPath = Paths.get(outputFile);
        java.nio.file.Files.createDirectories(outPath.getParent());

        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(inPath);
             java.io.BufferedWriter writer = java.nio.file.Files.newBufferedWriter(outPath)) {
            String line;
            int sentenceNum = 0;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    writer.newLine();
                    continue;
                }

                var tokens = tagger.tagSentence(line);
                for (var token : tokens) {
                    writer.write(token.toString());
                    writer.newLine();
                }
                writer.newLine();
                sentenceNum++;

                if (sentenceNum % 1000 == 0) {
                    System.out.println("Processed " + sentenceNum + " sentences...");
                }
            }
        }

        System.out.println("Tagging complete!");
    }

    private static void handleConllUCommand(String[] args) throws IOException {
        java.util.List<String> inputFiles = new java.util.ArrayList<>();
        String indexPath = null;
        int commitInterval = 100000;
        int numThreads = 1;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--input":
                case "-i":
                    inputFiles.add(args[++i]);
                    break;
                case "--output":
                case "-o":
                    indexPath = args[++i];
                    break;
                case "--commit":
                    commitInterval = Integer.parseInt(args[++i]);
                    break;
                case "--threads":
                    numThreads = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
            }
        }

        if (inputFiles.isEmpty() || indexPath == null) {
            System.err.println("Error: --input (one or more) and --output are required");
            System.err.println("Usage: java -jar word-sketch-lucene.jar conllu -i <file1> -i <file2> ... --output <path> [--threads <n>]");
            System.err.println("  Or use glob pattern: java -jar word-sketch-lucene.jar conllu -i 'D:/corpus_74m/temp/udpipe_*.conllu' --output <path>");
            return;
        }

        System.out.println("Processing " + inputFiles.size() + " CoNLL-U file(s)...");
        System.out.println("Output index: " + indexPath);
        System.out.println("Threads: " + numThreads);
        System.out.println("Commit interval: " + commitInterval);
        System.out.println();

        // Create processor once, reuse for all files (keeps index open)
        ConllUProcessor processor = ConllUProcessor.create(indexPath, numThreads);

        long totalSentences = 0;
        long startTime = System.currentTimeMillis();

        for (String inputFile : inputFiles) {
            java.io.File f = new java.io.File(inputFile);
            if (!f.exists()) {
                System.err.println("Warning: File not found, skipping: " + inputFile);
                continue;
            }

            System.out.println("Processing: " + inputFile + " (" + (f.length() / (1024*1024)) + " MB)");
            long fileStart = System.currentTimeMillis();

            processor.processFile(inputFile, commitInterval);

            long fileTime = System.currentTimeMillis() - fileStart;
            System.out.println("  Done in " + (fileTime / 1000) + "s");
        }

        processor.close();
        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println();
        System.out.println("Indexing complete! Total time: " + (totalTime / 1000) + "s");
    }

    private static void handleConvertCommand(String[] args) throws IOException {
        String inputFile = null;
        String outputFile = null;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--input":
                case "-i":
                    inputFile = args[++i];
                    break;
                case "--output":
                case "-o":
                    outputFile = args[++i];
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
            }
        }

        if (inputFile == null || outputFile == null) {
            System.err.println("Error: --input and --output are required");
            System.err.println("Usage: java -jar word-sketch-lucene.jar convert --input <file> --output <conllu-file>");
            return;
        }

        System.out.println("Converting: " + inputFile + " -> " + outputFile);
        System.out.println();

        ConllUProcessor.convertSimpleFormat(inputFile, outputFile);

        System.out.println("Conversion complete!");
    }

    private static void handleSnowballCommand(String[] args) throws IOException {
        String indexPath = null;
        String seedWords = null;
        String mode = "predicate";  // "predicate" or "linking"
        double minLogDice = 5.0;
        int maxPerIteration = 50;
        int maxDepth = 3;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--help":
                case "-h":
                    System.out.println("Snowball command (recursive collocation exploration):");
                    System.out.println("  java -jar word-sketch-lucene.jar snowball --index <path> --seeds <words>");
                    System.out.println("    [--mode predicate|linking] [--min-logdice <score>] [--depth <n>]");
                    System.out.println();
                    System.out.println("  Modes:");
                    System.out.println("    predicate: Explore adjectives -> nouns -> adjectives (default)");
                    System.out.println("    linking: Explore nouns via linking verbs (be, seem, appear, etc.)");
                    return;
                case "--index":
                case "-i":
                    indexPath = args[++i];
                    break;
                case "--seeds":
                case "-s":
                    seedWords = args[++i];
                    break;
                case "--mode":
                case "-m":
                    mode = args[++i].toLowerCase();
                    break;
                case "--min-logdice":
                    minLogDice = Double.parseDouble(args[++i]);
                    break;
                case "--per-iteration":
                    maxPerIteration = Integer.parseInt(args[++i]);
                    break;
                case "--depth":
                    maxDepth = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
            }
        }

        if (indexPath == null) {
            System.err.println("Error: --index is required");
            System.err.println("Usage: java -jar word-sketch-lucene.jar snowball --index <path> --seeds <words>");
            return;
        }

        if (seedWords == null) {
            System.err.println("Error: --seeds (comma-separated words) is required");
            System.err.println("Usage: java -jar word-sketch-lucene.jar snowball --index <path> --seeds <words>");
            return;
        }

        Set<String> seeds = new java.util.LinkedHashSet<>();
        for (String s : seedWords.split(",")) {
            seeds.add(s.trim().toLowerCase());
        }

        System.out.println("Snowball Collocation Explorer");
        System.out.println("==============================");
        System.out.println("Index: " + indexPath);
        System.out.println("Seed words: " + seeds);
        System.out.println("Mode: " + mode);
        System.out.println("Min logDice: " + minLogDice);
        System.out.println("Max per iteration: " + maxPerIteration);
        System.out.println("Max depth: " + maxDepth);
        System.out.println();

        try (SnowballCollocations explorer = new SnowballCollocations(indexPath)) {
            SnowballCollocations.SnowballResult result;
            if ("linking".equals(mode)) {
                // Treat seeds as nouns, find adjectives via linking verbs
                result = explorer.exploreLinkingVerbPredicates(seeds, minLogDice, maxPerIteration, maxDepth);
            } else {
                // Treat seeds as adjectives, explore adjective-noun graph
                result = explorer.exploreAsPredicates(seeds, minLogDice, maxPerIteration, maxDepth);
            }

            System.out.println("\n=== FINAL RESULT ===");
            System.out.println("Adjectives discovered (" + result.getAllAdjectives().size() + "):");
            System.out.println("  " + String.join(", ", result.getAllAdjectives()));

            System.out.println("\nNouns discovered (" + result.getAllNouns().size() + "):");
            System.out.println("  " + String.join(", ", result.getAllNouns()));

            System.out.println("\nTop edges (by logDice):");
            result.getEdges().stream()
                .sorted((a, b) -> Double.compare(b.weight, a.weight))
                .limit(20)
                .forEach(e -> System.out.printf("  %.2f: %s %s %s%n", e.weight, e.source, e.type, e.target));
        }
    }

    private static void showUsage() {
        System.out.println("Usage: java -jar word-sketch-lucene.jar <command> [options]");
        System.out.println();
        System.out.println("Available commands:");
        System.out.println("  index     - Index a corpus for word sketch analysis");
        System.out.println("  conllu    - Index a pre-tagged CoNLL-U file");
        System.out.println("  convert   - Convert simple tagged format to CoNLL-U");
        System.out.println("  query     - Query the word sketch index");
        System.out.println("  server    - Start the REST API server");
        System.out.println("  snowball  - Explore collocations recursively (snowball method)");
        System.out.println("  tag       - Tag a corpus with POS tags (uses simple tagger)");
        System.out.println("  help      - Show this help message");
        System.out.println();
        System.out.println("Index command (for raw text):");
        System.out.println("  java -jar word-sketch-lucene.jar index --corpus <file> --output <path> [--language <lang>]");
        System.out.println();
        System.out.println("CoNLL-U command (for pre-tagged files, supports multiple files):");
        System.out.println("  java -jar word-sketch-lucene.jar conllu -i <file1> -i <file2> ... --output <path> [--threads <n>]");
        System.out.println("  java -jar word-sketch-lucene.jar conllu -i 'D:/corpus_74m/temp/udpipe_*.conllu' --output <path>");
        System.out.println();
        System.out.println("Convert command (tagged -> CoNLL-U):");
        System.out.println("  java -jar word-sketch-lucene.jar convert --input <file> --output <conllu-file>");
        System.out.println();
        System.out.println("Query command:");
        System.out.println("  java -jar word-sketch-lucene.jar query --index <path> --lemma <word> [--pattern <cql>]");
        System.out.println();
        System.out.println("Server command:");
        System.out.println("  java -jar word-sketch-lucene.jar server --index <path> [--port <port>]");
        System.out.println();
        System.out.println("Snowball command (recursive collocation exploration):");
        System.out.println("  java -jar word-sketch-lucene.jar snowball --index <path> --seeds <words>");
        System.out.println("    [--mode predicate|linking] [--min-logdice <score>] [--depth <n>]");
        System.out.println();
        System.out.println("  Modes:");
        System.out.println("    predicate: Explore adjectives -> nouns -> adjectives (default)");
        System.out.println("    linking: Explore nouns via linking verbs (be, seem, appear, etc.)");
        System.out.println();
        System.out.println("Tag command (uses simple rule-based tagger):");
        System.out.println("  java -jar word-sketch-lucene.jar tag --input <file> --output <file>");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Index raw text using UDPipe (if available) or simple tagger:");
        System.out.println("  java -jar word-sketch-lucene.jar index --corpus sentences.txt --output data/index/");
        System.out.println();
        System.out.println("  # Index pre-tagged CoNLL-U file (recommended for large corpora):");
        System.out.println("  java -jar word-sketch-lucene.jar conllu --input corpus.conllu --output data/index/");
        System.out.println();
        System.out.println("  # Query the word sketch:");
        System.out.println("  java -jar word-sketch-lucene.jar query --index data/index/ --lemma house");
        System.out.println();
        System.out.println("  # Start API server:");
        System.out.println("  java -jar word-sketch-lucene.jar server --index data/index/");
        System.out.println();
        System.out.println("  # Explore collocations recursively (snowball method):");
        System.out.println("  java -jar word-sketch-lucene.jar snowball --index data/index/ --seeds big,small,important");
        System.out.println("  java -jar word-sketch-lucene.jar snowball --index data/index/ --seeds problem,solution --mode linking");
    }
}

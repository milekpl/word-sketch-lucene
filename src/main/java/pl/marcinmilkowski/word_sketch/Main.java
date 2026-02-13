package pl.marcinmilkowski.word_sketch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.api.WordSketchApiServer;
    import pl.marcinmilkowski.word_sketch.indexer.hybrid.CollocationsBuilderV2;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.HybridConllUProcessor;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.SinglePassConlluProcessor;
import pl.marcinmilkowski.word_sketch.query.HybridQueryExecutor;
import pl.marcinmilkowski.word_sketch.query.QueryExecutor;
import pl.marcinmilkowski.word_sketch.query.QueryExecutorFactory;
import pl.marcinmilkowski.word_sketch.query.SnowballCollocations;
import pl.marcinmilkowski.word_sketch.tagging.ConllUProcessor;
import pl.marcinmilkowski.word_sketch.tagging.SimpleTagger;
import pl.marcinmilkowski.word_sketch.tagging.UDPipeTagger;
import pl.marcinmilkowski.word_sketch.viz.SnowballVisualizer;

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
                case "convert":
                    handleConvertCommand(args);
                    break;
                case "snowball":
                    handleSnowballCommand(args);
                    break;
                case "hybrid-index":
                    handleHybridIndexCommand(args);
                    break;
                case "hybrid-query":
                    handleHybridQueryCommand(args);
                    break;
                case "single-pass":
                    handleSinglePassCommand(args);
                    break;
                case "precompute-collocations":
                    handlePrecomputeCollocationsCommand(args);
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
        throw new UnsupportedOperationException(
            "Legacy 'index' command has been removed. Use 'hybrid-index', 'single-pass', or 'precompute-collocations'.");
    }

    private static void handleQueryCommand(String[] args) throws IOException {
        String indexPath = null;
        String lemma = null;
        String cqlPattern = null;
        double minLogDice = 0;
        int limit = 50;
        int sampleSize = 10_000;  // Default: fast mode

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
                case "--sample":
                case "-s":
                    sampleSize = Integer.parseInt(args[++i]);
                    break;
                case "--accurate":
                    sampleSize = 50_000;  // Accurate mode
                    break;
                case "--fast":
                    sampleSize = 10_000;  // Fast mode (default)
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
            }
        }

        if (indexPath == null) {
            System.err.println("Error: --index is required");
            System.err.println("Usage: java -jar word-sketch-lucene.jar query --index <path> --lemma <word>");
            System.err.println("       [--sample <n>] or [--fast] or [--accurate]");
            return;
        }

        if (lemma == null) {
            System.err.println("Error: --lemma is required");
            System.err.println("Usage: java -jar word-sketch-lucene.jar query --index <path> --lemma <word>");
            return;
        }

        if (cqlPattern == null) {
            cqlPattern = "[lemma=\".*\"]";
        }

        System.out.println("Querying index: " + indexPath);
        System.out.println("Lemma: " + lemma);
        System.out.println("Pattern: " + cqlPattern);
        System.out.println("Min logDice: " + minLogDice);
        System.out.println("Limit: " + limit);
        System.out.println("Sample size: " + (sampleSize == 0 ? "unlimited" : sampleSize));
        System.out.println();

        try (HybridQueryExecutor executor = new HybridQueryExecutor(indexPath)) {
            executor.setMaxSampleSize(sampleSize);
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
        }
    }

    private static void handleServerCommand(String[] args) throws IOException {
        String indexPath = null;
        String collocationPath = null;
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
                case "--collocations":
                    collocationPath = args[++i];
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
            }
        }

        if (indexPath == null) {
            System.err.println("Error: --index is required");
            System.err.println("Usage: java -jar word-sketch-lucene.jar server --index <path> [--port <port>] [--collocations <path>]");
            return;
        }

        System.out.println("Starting API server...");
        System.out.println("Index: " + indexPath);
        if (collocationPath != null) {
            System.out.println("Collocations: " + collocationPath);
        }
        System.out.println("Port: " + port);
        System.out.println();
        System.out.println("Endpoints:");
        System.out.println("  GET  /health - Health check");
        System.out.println("  GET  /api/sketch/{lemma} - Get word sketch");
        System.out.println("  POST /api/sketch/query - Custom CQL query");
        System.out.println();
        System.out.println("Press Ctrl+C to stop the server.");
        System.out.println();

        QueryExecutor executor = QueryExecutorFactory.createAutoDetect(indexPath, collocationPath);
        WordSketchApiServer server = WordSketchApiServer.builder()
            .withExecutor(executor)
            .withIndexPath(indexPath)
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
        String outputDir = null;  // For visualization export
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
                    System.out.println("    [--output <dir>]  # Export visualizations (SVG, JSON, HTML)");
                    System.out.println();
                    System.out.println("  Modes:");
                    System.out.println("    predicate: Explore adjectives -> nouns -> adjectives (default)");
                    System.out.println("    linking: Explore nouns via linking verbs (be, seem, appear, etc.)");
                    System.out.println();
                    System.out.println("  Visualization output (when --output is specified):");
                    System.out.println("    - snowball_network.svg: Publication-quality network graph");
                    System.out.println("    - radial_<word>.svg: Radial plots for top words");
                    System.out.println("    - snowball_data.json: Data in JSON format");
                    System.out.println("    - viewer.html: Interactive HTML viewer");
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
                case "--output":
                case "-o":
                    outputDir = args[++i];
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

            
            // Export visualizations if output directory specified
            if (outputDir != null) {
                System.out.println("\n=== EXPORTING VISUALIZATIONS ===");
                SnowballVisualizer visualizer = new SnowballVisualizer(result);
                visualizer.exportAll(outputDir);
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

    private static void handleHybridIndexCommand(String[] args) throws IOException {
        String input = null;
        String outputIndex = null;
        String statsPath = null;
        int commitInterval = 50_000;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--input":
                case "-i":
                    input = args[++i];
                    break;
                case "--output":
                case "-o":
                    outputIndex = args[++i];
                    break;
                case "--stats":
                    statsPath = args[++i];
                    break;
                case "--commit-interval":
                    commitInterval = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
            }
        }

        if (input == null || outputIndex == null) {
            System.err.println("Error: --input and --output are required");
            System.err.println("Usage: hybrid-index --input <conllu-file-or-dir> --output <index-path> [--stats <stats-file>]");
            return;
        }

        if (statsPath == null) {
            statsPath = outputIndex + "/stats.tsv";
        }

        System.out.println("=== Hybrid Index Builder ===");
        System.out.println("Input: " + input);
        System.out.println("Output: " + outputIndex);
        System.out.println("Stats: " + statsPath);
        System.out.println();

        long startTime = System.currentTimeMillis();

        try (HybridConllUProcessor processor = new HybridConllUProcessor(outputIndex)) {
            processor.setCommitInterval(commitInterval);
            
            java.nio.file.Path inputPath = java.nio.file.Paths.get(input);
            
            if (java.nio.file.Files.isDirectory(inputPath)) {
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

    private static void handleHybridQueryCommand(String[] args) throws IOException {
        String indexPath = null;
        String lemma = null;
        String cqlPattern = null;
        double minLogDice = 0;
        int limit = 50;
        int sampleSize = 10_000;

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
                case "--sample":
                case "-s":
                    sampleSize = Integer.parseInt(args[++i]);
                    break;
                case "--accurate":
                    sampleSize = 50_000;
                    break;
                case "--fast":
                    sampleSize = 10_000;
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
            }
        }

        if (indexPath == null || lemma == null) {
            System.err.println("Error: --index and --lemma are required");
            System.err.println("Usage: hybrid-query --index <path> --lemma <word> [--pattern <cql>]");
            return;
        }

        if (cqlPattern == null) {
            cqlPattern = "[tag=\".*\"]";
        }

        System.out.println("Querying hybrid index: " + indexPath);
        System.out.println("Lemma: " + lemma);
        System.out.println("Pattern: " + cqlPattern);
        System.out.println("Sample size: " + sampleSize);
        System.out.println();

        try (HybridQueryExecutor executor = new HybridQueryExecutor(indexPath)) {
            executor.setMaxSampleSize(sampleSize);
            var results = executor.findCollocations(lemma, cqlPattern, minLogDice, limit);

            System.out.println("Results (" + results.size() + "):");
            System.out.println("--------");
            for (var result : results) {
                System.out.printf("  %s (%s): freq=%d, logDice=%.2f%n",
                    result.getLemma(), result.getPos(),
                    result.getFrequency(), result.getLogDice());
            }
        }
    }

    private static void handleSinglePassCommand(String[] args) throws IOException {
        String input = null;
        String outputIndex = null;
        String collocationsPath = null;
        int commitInterval = 50_000;
        int memoryThreshold = 10_000_000;  // 10M entries total before spill
        Integer numShards = null;
        Integer spillThreshold = null;
        int windowSize = 5;
        int topK = 100;
        int minFreq = 10;
        int minCooc = 2;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--input":
                case "-i":
                    input = args[++i];
                    break;
                case "--output":
                case "-o":
                    outputIndex = args[++i];
                    break;
                case "--collocations":
                case "-c":
                    collocationsPath = args[++i];
                    break;
                case "--commit":
                    commitInterval = Integer.parseInt(args[++i]);
                    break;
                case "--memory":
                    memoryThreshold = Integer.parseInt(args[++i]);
                    break;
                case "--shards":
                    numShards = Integer.parseInt(args[++i]);
                    break;
                case "--spill":
                    spillThreshold = Integer.parseInt(args[++i]);
                    break;
                case "--window":
                    windowSize = Integer.parseInt(args[++i]);
                    break;
                case "--top-k":
                    topK = Integer.parseInt(args[++i]);
                    break;
                case "--min-freq":
                    minFreq = Integer.parseInt(args[++i]);
                    break;
                case "--min-cooc":
                    minCooc = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
            }
        }

        if (input == null || outputIndex == null) {
            System.err.println("Error: --input and --output are required");
            System.err.println("Usage: single-pass --input <conllu-file-or-dir> --output <index-path> [options]");
            System.err.println("  --collocations, -c <path>  Output collocations.bin file path");
            System.err.println("  --commit <n>               Commit interval (default: 50000)");
            System.err.println("  --memory <n>               Max in-memory collocation entries before spill (default: 10M)");
            System.err.println("  --shards <n>               Number of shards (power of 2, default: 64)");
            System.err.println("  --spill <n>                Spill threshold per shard (overrides --memory)");
            return;
        }

        if (collocationsPath == null) {
            collocationsPath = outputIndex + "/collocations.bin";
        }

        // Compute sharding parameters
        if (numShards == null) {
            numShards = 64;
        }
        if (Integer.bitCount(numShards) != 1) {
            throw new IllegalArgumentException("--shards must be a power of 2");
        }
        if (spillThreshold == null) {
            spillThreshold = Math.max(100_000, memoryThreshold / numShards);
        }

        System.out.println("=== Single-Pass Index + Collocations Builder ===");
        System.out.println("Input: " + input);
        System.out.println("Output index: " + outputIndex);
        System.out.println("Collocations: " + collocationsPath);
        System.out.println("Commit interval: " + commitInterval);
        System.out.println("Memory threshold: " + memoryThreshold + " entries");
        System.out.println("Shards: " + numShards);
        System.out.println("Spill threshold/shard: " + spillThreshold);
        System.out.println("Window: " + windowSize);
        System.out.println("Top-K: " + topK);
        System.out.println("Min freq: " + minFreq);
        System.out.println("Min cooc: " + minCooc);
        System.out.println();

        long startTime = System.currentTimeMillis();

        try (SinglePassConlluProcessor processor = new SinglePassConlluProcessor(
            outputIndex, collocationsPath, numShards, spillThreshold)) {
            
            processor.setCommitInterval(commitInterval);
            processor.setWindowSize(windowSize);
            processor.setTopK(topK);
            processor.setMinHeadwordFrequency(minFreq);
            processor.setMinCooccurrence(minCooc);
            
            java.nio.file.Path inputPath = java.nio.file.Paths.get(input);
            
            if (java.nio.file.Files.isDirectory(inputPath)) {
                processor.processDirectory(input, "*.conllu");
            } else {
                processor.processFile(input);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println();
            System.out.println("=== Complete ===");
            System.out.println("Sentences: " + processor.getSentenceCount());
            System.out.println("Tokens: " + processor.getTokenCount());
            System.out.println("Time: " + (elapsed / 1000) + "s");
            System.out.println("Rate: " + String.format("%.0f", processor.getTokenCount() / (elapsed / 1000.0)) + " tokens/s");
            System.out.println();
            System.out.println("Output files:");
            System.out.println("  Index:         " + outputIndex);
            System.out.println("  Collocations:  " + collocationsPath);
            System.out.println("  Statistics:    " + outputIndex + "/stats.bin");
            System.out.println("  Lexicon:       " + outputIndex + "/lexicon.bin");
        }
    }

    private static void handlePrecomputeCollocationsCommand(String[] args) throws IOException {
        String indexPath = null;
        String outputPath = null;
        int windowSize = 5;
        int topK = 100;
        int minFreq = 10;
        int minCooc = 2;
        int numShards = 64;
        int spillThreshold = 2_000_000;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--index":
                case "-i":
                    indexPath = args[++i];
                    break;
                case "--output":
                case "-o":
                    outputPath = args[++i];
                    break;
                case "--window":
                    windowSize = Integer.parseInt(args[++i]);
                    break;
                case "--top-k":
                    topK = Integer.parseInt(args[++i]);
                    break;
                case "--min-freq":
                    minFreq = Integer.parseInt(args[++i]);
                    break;
                case "--min-cooc":
                    minCooc = Integer.parseInt(args[++i]);
                    break;
                case "--shards":
                    numShards = Integer.parseInt(args[++i]);
                    break;
                case "--spill":
                    spillThreshold = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.err.println("Unknown option: " + args[i]);
            }
        }

        if (indexPath == null) {
            System.err.println("Error: --index is required");
            System.err.println("Usage: precompute-collocations --index <hybrid-index-path> [--output <collocations.bin>] [options]");
            return;
        }

        if (outputPath == null) {
            outputPath = indexPath + "/collocations.bin";
        }

        if (Integer.bitCount(numShards) != 1) {
            throw new IllegalArgumentException("--shards must be a power of 2");
        }

        System.out.println("=== Precompute Collocations (V2) ===");
        System.out.println("Index: " + indexPath);
        System.out.println("Output: " + outputPath);
        System.out.println("Window: " + windowSize);
        System.out.println("Top-K: " + topK);
        System.out.println("Min freq: " + minFreq);
        System.out.println("Min cooc: " + minCooc);
        System.out.println("Shards: " + numShards);
        System.out.println("Spill threshold/shard: " + spillThreshold);
        System.out.println();

        long startTime = System.currentTimeMillis();
        CollocationsBuilderV2 builder = new CollocationsBuilderV2(indexPath);
        builder.setWindowSize(windowSize);
        builder.setTopK(topK);
        builder.setMinHeadwordFrequency(minFreq);
        builder.setMinCooccurrence(minCooc);
        builder.setNumShards(numShards);
        builder.setSpillThresholdPerShard(spillThreshold);
        builder.build(outputPath);

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("Collocations precompute complete in " + (elapsed / 1000) + "s");
    }

    private static void showUsage() {
        System.out.println("Usage: java -jar word-sketch-lucene.jar <command> [options]");
        System.out.println();
        System.out.println("Available commands:");
        System.out.println("  index        - (removed) legacy text indexing command");
        System.out.println("  convert      - Convert simple tagged format to CoNLL-U");
        System.out.println("  query        - Query the word sketch index");
        System.out.println("  server       - Start the REST API server");
        System.out.println("  snowball     - Explore collocations recursively (snowball method)");
        System.out.println("  tag          - Tag a corpus with POS tags (uses simple tagger)");
        System.out.println("  hybrid-index - Build hybrid sentence-per-document index from CoNLL-U");
        System.out.println("  precompute-collocations - Build collocations.bin from existing hybrid index");
        System.out.println("  hybrid-query - Query a hybrid index");
        System.out.println("  single-pass  - Build index AND collocations in one pass");
        System.out.println("  help         - Show this help message");
        System.out.println();
        System.out.println("Index command (legacy):");
        System.out.println("  java -jar word-sketch-lucene.jar index ...   # removed");
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
        System.out.println("    [--output <dir>]  # Export publication-quality visualizations");
        System.out.println();
        System.out.println("  Modes:");
        System.out.println("    predicate: Explore adjectives -> nouns -> adjectives (default)");
        System.out.println("    linking: Explore nouns via linking verbs (be, seem, appear, etc.)");
        System.out.println();
        System.out.println("  Visualization output (when --output is specified):");
        System.out.println("    - SVG network graph (vector format for publications)");
        System.out.println("    - Radial plots for top words (ported from your Python code)");
        System.out.println("    - JSON data export");
        System.out.println("    - Interactive HTML viewer");
        System.out.println();
        System.out.println("Tag command (uses simple rule-based tagger):");
        System.out.println("  java -jar word-sketch-lucene.jar tag --input <file> --output <file>");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Build hybrid index from CoNLL-U file or directory:");
        System.out.println("  java -jar word-sketch-lucene.jar hybrid-index --input corpus.conllu --output data/index/");
        System.out.println("  java -jar word-sketch-lucene.jar hybrid-index --input corpus_dir/ --output data/index/");
        System.out.println();
        System.out.println("  # Precompute collocations from existing index with explicit sharding:");
        System.out.println("  java -jar word-sketch-lucene.jar precompute-collocations --index data/index/ --output data/index/collocations.bin --shards 64 --spill 2000000");
        System.out.println();
        System.out.println("  # Query the word sketch:");
        System.out.println("  java -jar word-sketch-lucene.jar query --index data/index/ --lemma house");
        System.out.println();
        System.out.println("  # Start API server:");
        System.out.println("  java -jar word-sketch-lucene.jar server --index data/index/");
        System.out.println();
        System.out.println("  # Explore collocations recursively (snowball method):");
        System.out.println("  java -jar word-sketch-lucene.jar snowball --index data/index/ --seeds big,small,important");
        System.out.println("  java -jar word-sketch-lucene.jar snowball --index data/index/ --seeds problem,solution --mode linking --output viz/");
        System.out.println();
        System.out.println("Hybrid query commands:");
        System.out.println("  # Query hybrid index:");
        System.out.println("  java -jar word-sketch-lucene.jar hybrid-query --index data/hybrid/ --lemma house");
        System.out.println("  java -jar word-sketch-lucene.jar hybrid-query --index data/hybrid/ --lemma house --pattern '[tag=\"JJ.*\"]'");
        System.out.println();
        System.out.println("Single-pass command (build index + collocations in one pass, recommended):");
        System.out.println("  # Build index and collocations from CoNLL-U files:");
        System.out.println("  java -jar word-sketch-lucene.jar single-pass --input corpus.conllu --output data/index/");
        System.out.println("  java -jar word-sketch-lucene.jar single-pass -i corpus_dir/ -o data/index/ -c data/index/collocations.bin");
        System.out.println();
        System.out.println("  # With custom options:");
        System.out.println("  java -jar word-sketch-lucene.jar single-pass -i corpus.conllu -o data/index/ \\");
        System.out.println("      --shards 64 --spill 2000000 --window 5 --top-k 100 --min-freq 10 --min-cooc 2");
    }
}

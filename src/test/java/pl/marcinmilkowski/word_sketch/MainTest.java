package pl.marcinmilkowski.word_sketch;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Main} CLI argument parsing and command dispatch.
 * Live index operations are not tested; only argument validation and usage output.
 */
class MainTest {

    private static String captureOut(Runnable action) {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buf.toString();
    }

    private static String captureErr(Runnable action) {
        PrintStream original = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return buf.toString();
    }

    @Test
    void noArgs_printsUsage() {
        String out = captureOut(() -> Main.main(new String[]{}));
        assertTrue(out.contains("Usage:"), "No-arg invocation must print usage");
        assertTrue(out.contains("blacklab-index"), "Usage must list blacklab-index command");
    }

    @Test
    void helpCommand_printsUsage() {
        String out = captureOut(() -> Main.main(new String[]{"help"}));
        assertTrue(out.contains("Usage:"), "help command must print usage");
    }

    @Test
    void unknownCommand_printsUsageToErr() {
        String err = captureErr(() -> Main.main(new String[]{"does-not-exist"}));
        String out = captureOut(() -> Main.main(new String[]{"does-not-exist"}));
        // Unknown command logs error and then prints usage — at least one of them contains "Usage"
        assertTrue(err.contains("does-not-exist") || out.contains("Usage:"),
                "Unknown command must produce an error message or usage output");
    }

    @Test
    void blacklabIndex_missingRequiredArgs_printsError() {
        String err = captureErr(() -> Main.main(new String[]{"blacklab-index"}));
        assertTrue(err.contains("--input") || err.contains("required"),
                "Missing args for blacklab-index should mention --input");
    }

    @Test
    void blacklabQuery_missingRequiredArgs_printsError() {
        String err = captureErr(() -> Main.main(new String[]{"blacklab-query"}));
        assertTrue(err.contains("--index") || err.contains("--lemma") || err.contains("required"),
                "Missing args for blacklab-query should mention required options");
    }

    @Test
    void server_missingRequiredArgs_printsError() {
        String err = captureErr(() -> Main.main(new String[]{"server"}));
        assertTrue(err.contains("--index") || err.contains("required"),
                "Missing --index for server should produce an error");
    }
}

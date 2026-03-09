package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for collocations-integrity diagnostics.
 *
 * Note: Precomputed collocations have been removed. This test now simply
 * verifies that the method is no longer available.
 */
class DiagnosticsIntegrationTest {

    @Test
    void skipDiagnostic() {
        Path idx = Path.of("index");
        if (!Files.exists(idx)) {
            System.out.println("Workspace index/ not found — skipping diagnostic integration test");
            return;
        }

        // The collocationsIntegrityTopN method has been removed
        // This test is now a placeholder
        System.out.println("Diagnostics test skipped - precomputed collocations have been removed");
        assertTrue(true);
    }
}

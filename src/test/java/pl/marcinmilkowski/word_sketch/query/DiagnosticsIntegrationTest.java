package pl.marcinmilkowski.word_sketch.query;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quick integration test to run collocations-integrity against workspace `index/` if present.
 */
public class DiagnosticsIntegrationTest {

    @Test
    void runDiagnostic() throws IOException {
        Path idx = Path.of("index");
        if (!Files.exists(idx)) {
            System.out.println("Workspace index/ not found — skipping diagnostic integration test");
            return;
        }

        try (HybridQueryExecutor h = new HybridQueryExecutor("index")) {
            // If collocations.bin isn't present, the executor will fall back / return empty report
            List<Map<String, Object>> report = h.collocationsIntegrityTopN(10);
            assertNotNull(report);
            System.out.println("collocation-integrity report size=" + report.size());
            for (var r : report) {
                System.out.println(r);
            }
        }
    }
}

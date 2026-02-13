package pl.marcinmilkowski.word_sketch.tools;

import com.alibaba.fastjson2.JSON;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.Bits;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.CollocationsReader;
import pl.marcinmilkowski.word_sketch.indexer.hybrid.StatisticsReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

public class IndexFingerprintTool {

    public static void main(String[] args) throws Exception {
        Map<String, String> params = parseArgs(args);

        String indexArg = params.get("index");
        if (indexArg == null || indexArg.isBlank()) {
            System.err.println("Usage: IndexFingerprintTool --index <path> [--collocations <path>] [--output <path>]");
            System.exit(1);
            return;
        }

        Path indexPath = Paths.get(indexArg);
        if (!Files.exists(indexPath)) {
            throw new IOException("Index path does not exist: " + indexPath);
        }

        Path collocationsPath = params.containsKey("collocations")
            ? Paths.get(params.get("collocations"))
            : indexPath.resolve("collocations.bin");
        Path statsBinPath = indexPath.resolve("stats.bin");
        Path outputPath = params.containsKey("output") ? Paths.get(params.get("output")) : null;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timestamp_utc", Instant.now().toString());
        out.put("index_path", indexPath.toAbsolutePath().toString());

        try (DirectoryReader reader = DirectoryReader.open(MMapDirectory.open(indexPath))) {
            out.put("doc_count", reader.numDocs());
            out.put("max_doc", reader.maxDoc());
            out.put("segment_count", reader.leaves().size());

            List<String> firstSegmentFields = firstSegmentFields(reader.leaves());
            out.put("first_segment_fields", firstSegmentFields);

            Map<String, Boolean> required = new LinkedHashMap<>();
            for (String f : Arrays.asList("sentence_id", "doc_id", "tokens", "lemma_ids", "text")) {
                required.put(f, firstSegmentFields.contains(f));
            }
            out.put("first_segment_required_presence", required);

            String indexType = required.getOrDefault("sentence_id", false) ? "hybrid"
                : (required.getOrDefault("doc_id", false) ? "legacy" : "unknown");
            out.put("detected_index_type", indexType);

            out.put("sample_stored_fields", sampleStoredFields(reader.leaves()));
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("stats_bin_path", statsBinPath.toAbsolutePath().toString());
        stats.put("exists", Files.exists(statsBinPath));
        if (Files.exists(statsBinPath)) {
            try (StatisticsReader sr = new StatisticsReader(statsBinPath.toString())) {
                stats.put("lemma_count", sr.getUniqueLemmaCount());
                stats.put("total_tokens", sr.getTotalTokens());
                stats.put("total_sentences", sr.getTotalSentences());
            }
        }
        out.put("stats", stats);

        Map<String, Object> collocations = new LinkedHashMap<>();
        collocations.put("path", collocationsPath.toAbsolutePath().toString());
        collocations.put("exists", Files.exists(collocationsPath));
        if (Files.exists(collocationsPath)) {
            try (CollocationsReader cr = new CollocationsReader(collocationsPath.toString())) {
                collocations.put("entry_count", cr.getEntryCount());
                collocations.put("window", cr.getWindowSize());
                collocations.put("top_k", cr.getTopK());
                collocations.put("total_corpus_tokens", cr.getTotalCorpusTokens());
            }
        }
        out.put("collocations", collocations);

        String pretty = JSON.toJSONString(out, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
        if (outputPath != null) {
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            Files.writeString(outputPath, pretty);
            System.out.println("Fingerprint written: " + outputPath.toAbsolutePath());
        } else {
            System.out.println(pretty);
        }
    }

    private static List<String> firstSegmentFields(List<LeafReaderContext> leaves) {
        if (leaves.isEmpty()) {
            return List.of();
        }
        TreeSet<String> names = new TreeSet<>();
        for (FieldInfo fi : leaves.get(0).reader().getFieldInfos()) {
            names.add(fi.name);
        }
        return new ArrayList<>(names);
    }

    private static Map<String, Object> sampleStoredFields(List<LeafReaderContext> leaves) throws IOException {
        for (LeafReaderContext leaf : leaves) {
            Bits live = leaf.reader().getLiveDocs();
            for (int doc = 0; doc < leaf.reader().maxDoc(); doc++) {
                if (live != null && !live.get(doc)) {
                    continue;
                }
                Document d = leaf.reader().storedFields().document(doc);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("leaf", leaf.ord);
                out.put("doc", doc);
                Map<String, String> fields = new HashMap<>();
                d.getFields().forEach(f -> {
                    if (f.stringValue() != null) {
                        String value = f.stringValue();
                        String compact = value.length() > 120 ? value.substring(0, 120) + "..." : value;
                        fields.put(f.name(), compact);
                    }
                });
                out.put("stored", fields);
                return out;
            }
        }
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("note", "no live docs with stored fields found");
        return empty;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            String key = arg.substring(2).toLowerCase(Locale.ROOT);
            String value = "true";
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[++i];
            }
            out.put(key, value);
        }
        return out;
    }
}

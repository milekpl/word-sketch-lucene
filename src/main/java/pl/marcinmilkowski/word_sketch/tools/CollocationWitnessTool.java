package pl.marcinmilkowski.word_sketch.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.spans.SpanNearQuery;
import org.apache.lucene.queries.spans.SpanTermQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.MMapDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CollocationWitnessTool {

    public static void main(String[] args) throws Exception {
        Map<String, String> params = parseArgs(args);
        String indexArg = params.get("index");
        String reportArg = params.get("report");

        if (indexArg == null || reportArg == null) {
            System.err.println("Usage: CollocationWitnessTool --index <path> --report <integrity_report.raw.json> [--output <path>] [--headwords a,b] [--window 5] [--per-head-limit 100]");
            System.exit(1);
            return;
        }

        Path indexPath = Paths.get(indexArg);
        Path reportPath = Paths.get(reportArg);
        Path outputPath = params.containsKey("output")
            ? Paths.get(params.get("output"))
            : defaultOutputPath();
        int window = parseInt(params.getOrDefault("window", "5"), 5);
        int perHeadLimit = parseInt(params.getOrDefault("per-head-limit", "100"), 100);

        Set<String> headFilter = new HashSet<>();
        String headsArg = params.get("headwords");
        if (headsArg != null && !headsArg.isBlank()) {
            for (String h : headsArg.split(",")) {
                String cleaned = h.trim().toLowerCase(Locale.ROOT);
                if (!cleaned.isEmpty()) {
                    headFilter.add(cleaned);
                }
            }
        }

        String raw = Files.readString(reportPath);
        JSONObject root = JSON.parseObject(raw);
        JSONArray report = root.getJSONArray("report");
        if (report == null) {
            throw new IOException("Expected JSON object with 'report' array: " + reportPath);
        }

        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }

        List<String> lines = new ArrayList<>();
        lines.add("headword\tcollocate\treason\tdocfreq_head\tdocfreq_collocate\tspan_hits\tsample_doc_ids\tclassification");

        try (DirectoryReader reader = DirectoryReader.open(MMapDirectory.open(indexPath))) {
            IndexSearcher searcher = new IndexSearcher(reader);

            for (int i = 0; i < report.size(); i++) {
                JSONObject item = report.getJSONObject(i);
                if (item == null) {
                    continue;
                }

                String head = normalize(item.getString("headword"));
                if (head.isEmpty()) {
                    continue;
                }
                if (!headFilter.isEmpty() && !headFilter.contains(head)) {
                    continue;
                }

                JSONArray problems = item.getJSONArray("problems");
                if (problems == null || problems.isEmpty()) {
                    continue;
                }

                int limit = Math.min(perHeadLimit, problems.size());
                for (int j = 0; j < limit; j++) {
                    JSONObject p = problems.getJSONObject(j);
                    if (p == null) {
                        continue;
                    }

                    String coll = normalize(p.getString("lemma"));
                    String reason = safe(p.getString("reason"));

                    long headDf = searcher.count(new TermQuery(new Term("lemma", head)));
                    long collDf = searcher.count(new TermQuery(new Term("lemma", coll)));

                    TopDocs td = searcher.search(
                        SpanNearQuery.newUnorderedNearQuery("lemma")
                            .addClause(new SpanTermQuery(new Term("lemma", head)))
                            .addClause(new SpanTermQuery(new Term("lemma", coll)))
                            .setSlop(window)
                            .build(),
                        5
                    );

                    long spanHits = td.totalHits.value();
                    String sampleDocIds = sampleDocIds(td.scoreDocs);
                    String classification = classify(head, coll, headDf, collDf, spanHits);

                    lines.add(String.join("\t",
                        tsv(head),
                        tsv(coll),
                        tsv(reason),
                        String.valueOf(headDf),
                        String.valueOf(collDf),
                        String.valueOf(spanHits),
                        tsv(sampleDocIds),
                        tsv(classification)
                    ));
                }
            }
        }

        Files.write(outputPath, lines);
        System.out.println("Witness TSV written: " + outputPath.toAbsolutePath());
    }

    private static Path defaultOutputPath() {
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withLocale(Locale.ROOT)
            .format(Instant.now().atZone(java.time.ZoneOffset.UTC));
        return Paths.get("diagnostics", "witness_" + ts + ".tsv");
    }

    private static String classify(String head, String coll, long headDf, long collDf, long spanHits) {
        if (isMalformedLemmaPattern(head) || isMalformedLemmaPattern(coll)) {
            return "malformed_lemma_pattern";
        }
        if (headDf == 0) {
            return "missing_headword";
        }
        if (collDf == 0) {
            return "missing_collocate";
        }
        if (spanHits == 0) {
            return "both_present_but_no_span";
        }
        return "witness_found";
    }

    private static boolean isMalformedLemmaPattern(String lemma) {
        if (lemma == null || lemma.isBlank()) {
            return true;
        }
        String v = lemma.toLowerCase(Locale.ROOT);
        if (v.contains("pmid") || v.contains("doi")) {
            return true;
        }
        int letters = 0;
        int digits = 0;
        int symbols = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isLetter(c)) letters++;
            else if (Character.isDigit(c)) digits++;
            else symbols++;
        }
        return letters == 0 || symbols > letters || digits > letters;
    }

    private static String sampleDocIds(ScoreDoc[] scoreDocs) {
        if (scoreDocs == null || scoreDocs.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < scoreDocs.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(scoreDocs[i].doc);
        }
        return sb.toString();
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

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String tsv(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}

package pl.marcinmilkowski.word_sketch.indexer.hybrid;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.Bits;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Locale;

/**
 * Single-pass (over sentences) collocations builder.
 *
 * Requires a hybrid index with BinaryDocValues field "lemma_ids" and a "lexicon.bin".
 *
 * Pipeline (initial implementation):
 * - Scan all sentence docs once and spill sorted runs per shard
 * - Reduce by shard via multi-way merge and write collocations.bin
 */
public class CollocationsBuilderV2 {

    private static final Logger log = LoggerFactory.getLogger(CollocationsBuilderV2.class);

    // Configuration
    private int windowSize = 5;
    private int topK = 100;
    private int minHeadwordFrequency = 10;
    private int minCooccurrence = 2;
    private int numShards = 64; // power of 2

    // Spill threshold (approx number of distinct pairs in a shard map)
    private int spillThresholdPerShard = 2_000_000;

    private final Path indexPath;

    public CollocationsBuilderV2(String indexPath) {
        this.indexPath = Paths.get(indexPath);
    }

    public void setWindowSize(int windowSize) {
        this.windowSize = windowSize;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public void setMinHeadwordFrequency(int minHeadwordFrequency) {
        this.minHeadwordFrequency = minHeadwordFrequency;
    }

    public void setMinCooccurrence(int minCooccurrence) {
        this.minCooccurrence = minCooccurrence;
    }

    public void setNumShards(int numShards) {
        if (Integer.bitCount(numShards) != 1) {
            throw new IllegalArgumentException("numShards must be a power of 2");
        }
        this.numShards = numShards;
    }

    public void setSpillThresholdPerShard(int spillThresholdPerShard) {
        this.spillThresholdPerShard = Math.max(10_000, spillThresholdPerShard);
    }

    public void build(String outputPath) throws IOException {
        Path lexiconPath = indexPath.resolve("lexicon.bin");
        if (!Files.exists(lexiconPath)) {
            throw new IOException("Missing lexicon.bin in index directory: " + lexiconPath + " (rebuild the index with this version)");
        }

        Path workDir = Paths.get(outputPath + ".work");
        Path pairsDir = workDir.resolve("pairs");
        Files.createDirectories(pairsDir);

        try (LemmaLexiconReader lexicon = new LemmaLexiconReader(lexiconPath.toString());
             var reader = DirectoryReader.open(MMapDirectory.open(indexPath))) {

            log.info("CollocationsBuilderV2 initialized:");
            log.info("  Index: {} sentences", reader.numDocs());
            log.info("  Lexicon: {} lemmas, {} tokens", lexicon.size(), lexicon.getTotalTokens());
            log.info("  Config: window={}, topK={}, minFreq={}, minCooc={}, shards={}",
                windowSize, topK, minHeadwordFrequency, minCooccurrence, numShards);

            // Scan + spill
            int runId = scanAndSpill(reader.leaves(), lexicon, pairsDir);

            // Reduce + write final output
            reduceAndWrite(pairsDir, lexicon, reader, outputPath);

            log.info("Build completed (runs written: {}). Output: {}", runId, outputPath);
        }
    }

    private int scanAndSpill(List<LeafReaderContext> leaves, LemmaLexiconReader lexicon, Path pairsDir) throws IOException {
        LongIntHashMap[] shardMaps = new LongIntHashMap[numShards];
        for (int i = 0; i < numShards; i++) {
            shardMaps[i] = new LongIntHashMap(1024 * 1024);
        }

        IntArrayBuffer idsBuf = new IntArrayBuffer(64);

        long docsProcessed = 0;
        long tokensProcessed = 0;
        int runId = 0;

        for (int leafOrd = 0; leafOrd < leaves.size(); leafOrd++) {
            LeafReaderContext leaf = leaves.get(leafOrd);
            BinaryDocValues lemmaIdsDv = leaf.reader().getBinaryDocValues("lemma_ids");
            if (lemmaIdsDv == null) {
                throw new IOException("Index segment is missing required 'lemma_ids' BinaryDocValues (leaf="
                    + leafOrd + "). Rebuild index with hybrid single-pass pipeline.");
            }

            Bits liveDocs = leaf.reader().getLiveDocs();
            int maxDoc = leaf.reader().maxDoc();
            for (int doc = 0; doc < maxDoc; doc++) {
                if (liveDocs != null && !liveDocs.get(doc)) {
                    continue;
                }

                if (!lemmaIdsDv.advanceExact(doc)) {
                    throw new IOException("Live document is missing required 'lemma_ids' BinaryDocValues (leaf="
                        + leafOrd + ", doc=" + doc + ")");
                }

                BytesRef bytes = lemmaIdsDv.binaryValue();
                int len = LemmaIdsCodec.decodeTo(bytes, idsBuf);
                int[] ids = idsBuf.array();

                tokensProcessed += len;

                for (int i = 0; i < len; i++) {
                    int headId = ids[i];
                    if (headId < 0 || headId >= lexicon.size()) {
                        continue;
                    }

                    long headFreq = lexicon.getFrequency(headId);
                    if (headFreq < minHeadwordFrequency) {
                        continue;
                    }

                    int start = Math.max(0, i - windowSize);
                    int end = Math.min(len, i + windowSize + 1);

                    int shard = headId & (numShards - 1);
                    LongIntHashMap map = shardMaps[shard];

                    for (int j = start; j < end; j++) {
                        if (j == i) continue;
                        int collId = ids[j];
                        if (collId == headId) continue;

                        long key = (((long) headId) << 32) | (collId & 0xffffffffL);
                        map.addTo(key, 1);
                    }
                }

                docsProcessed++;

                // Spill if any shard is too large
                if ((docsProcessed % 50_000) == 0) {
                    long mb = tokensProcessed / (1024 * 1024);
                    log.info("Scan progress: {} docs, ~{}M tokens, runId={} (spill threshold {})",
                        docsProcessed, mb, runId, spillThresholdPerShard);
                }

                boolean needSpill = false;
                for (LongIntHashMap m : shardMaps) {
                    if (m.size() >= spillThresholdPerShard) {
                        needSpill = true;
                        break;
                    }
                }

                if (needSpill) {
                    spillAllShards(shardMaps, pairsDir, runId);
                    runId++;
                }
            }
        }

        // Final spill
        spillAllShards(shardMaps, pairsDir, runId);
        runId++;

        log.info("Scan complete: {} docs, {} tokens", docsProcessed, tokensProcessed);
        return runId;
    }

    private void spillAllShards(LongIntHashMap[] shardMaps, Path pairsDir, int runId) throws IOException {
        for (int shard = 0; shard < shardMaps.length; shard++) {
            LongIntHashMap map = shardMaps[shard];
            if (map.isEmpty()) continue;

            // Extract dense arrays
            long[] keys = new long[map.size()];
            int[] values = new int[map.size()];
            final int[] idx = new int[] {0};
            map.forEach((k, v) -> {
                int i = idx[0]++;
                keys[i] = k;
                values[i] = v;
            });

            LongIntPairSort.sort(keys, values, 0, keys.length);

            Path shardDir = pairsDir.resolve(String.format(Locale.ROOT, "shard-%03d", shard));
            Files.createDirectories(shardDir);
            Path runFile = shardDir.resolve(String.format(Locale.ROOT, "run-%06d.bin", runId));
            PairRunIO.writeRun(runFile, keys, values, keys.length);

            map.clear();
        }

        log.info("Spilled run {}", runId);
    }

    private void reduceAndWrite(Path pairsDir, LemmaLexiconReader lexicon, IndexReader reader, String outputPath) throws IOException {
        long droppedMissingHead = 0;
        long droppedMissingColl = 0;
        byte[] lemmaIndexedCache = new byte[Math.max(1, lexicon.size())];
        try (CollocationsBinWriter writer = new CollocationsBinWriter(outputPath, windowSize, topK, lexicon.getTotalTokens())) {
            for (int shard = 0; shard < numShards; shard++) {
                Path shardDir = pairsDir.resolve(String.format(Locale.ROOT, "shard-%03d", shard));
                if (!Files.exists(shardDir)) continue;

                List<Path> runFiles;
                try (var stream = Files.list(shardDir)) {
                    runFiles = stream
                        .filter(p -> p.getFileName().toString().endsWith(".bin"))
                        .sorted()
                        .toList();
                }
                if (runFiles.isEmpty()) continue;

                log.info("Reducing shard {} ({} runs)", shard, runFiles.size());
                long[] dropped = reduceShard(runFiles, lexicon, reader, lemmaIndexedCache, writer);
                droppedMissingHead += dropped[0];
                droppedMissingColl += dropped[1];
            }

            writer.finalizeFile();
            log.info("collocations.bin entries: {}", writer.getEntryCount());
            log.info("Dropped candidates due to index lemma absence: heads={}, collocates={}", droppedMissingHead, droppedMissingColl);
        }
    }

    private long[] reduceShard(List<Path> runFiles,
                               LemmaLexiconReader lexicon,
                               IndexReader reader,
                               byte[] lemmaIndexedCache,
                               CollocationsBinWriter writer) throws IOException {
        long droppedMissingHead = 0;
        long droppedMissingColl = 0;
        // Open cursors and prime them
        List<PairRunIO.RunCursor> cursors = new ArrayList<>(runFiles.size());
        try {
            for (Path p : runFiles) {
                PairRunIO.RunCursor c = PairRunIO.openCursor(p);
                if (c.advance()) {
                    cursors.add(c);
                } else {
                    c.close();
                }
            }

            PriorityQueue<PairRunIO.RunCursor> pq = new PriorityQueue<>(Comparator.comparingLong(c -> c.key));
            pq.addAll(cursors);

            int currentHeadId = -1;
            long currentHeadFreq = 0;

            PriorityQueue<Candidate> top = new PriorityQueue<>(Comparator.comparingDouble(c -> c.logDice));

            while (!pq.isEmpty()) {
                PairRunIO.RunCursor c = pq.poll();

                long key = c.key;
                int count = c.value;

                if (c.advance()) {
                    pq.add(c);
                } else {
                    c.close();
                }

                // Aggregate identical keys across cursors
                while (!pq.isEmpty() && pq.peek().key == key) {
                    PairRunIO.RunCursor c2 = pq.poll();
                    count += c2.value;
                    if (c2.advance()) {
                        pq.add(c2);
                    } else {
                        c2.close();
                    }
                }

                int headId = (int) (key >>> 32);
                int collId = (int) key;

                if (headId != currentHeadId) {
                    // flush previous head
                    if (currentHeadId >= 0 && !top.isEmpty()) {
                        Candidate[] arr = top.toArray(new Candidate[0]);
                        // sort descending by logDice
                        java.util.Arrays.sort(arr, (a, b) -> Float.compare(b.logDice, a.logDice));
                        writer.writeEntry(currentHeadId, currentHeadFreq, arr, arr.length, lexicon);
                    }
                    top.clear();

                    currentHeadId = headId;
                    if (headId >= 0 && headId < lexicon.size()) {
                        currentHeadFreq = lexicon.getFrequency(headId);
                    } else {
                        currentHeadFreq = 0;
                    }
                }

                if (count < minCooccurrence) {
                    continue;
                }
                if (currentHeadFreq < minHeadwordFrequency) {
                    continue;
                }
                if (collId < 0 || collId >= lexicon.size()) {
                    continue;
                }

                if (!isLemmaIndexed(headId, lexicon, reader, lemmaIndexedCache)) {
                    droppedMissingHead++;
                    continue;
                }

                if (!isLemmaIndexed(collId, lexicon, reader, lemmaIndexedCache)) {
                    droppedMissingColl++;
                    continue;
                }

                long collFreq = lexicon.getFrequency(collId);
                if (collFreq <= 0) {
                    continue;
                }

                float logDice = (float) calculateLogDice(count, currentHeadFreq, collFreq);

                Candidate cand = new Candidate(collId, count, collFreq, logDice);
                if (top.size() < topK) {
                    top.add(cand);
                } else if (top.peek().logDice < cand.logDice) {
                    top.poll();
                    top.add(cand);
                }
            }

            // flush last head
            if (currentHeadId >= 0 && !top.isEmpty()) {
                Candidate[] arr = top.toArray(new Candidate[0]);
                java.util.Arrays.sort(arr, (a, b) -> Float.compare(b.logDice, a.logDice));
                writer.writeEntry(currentHeadId, currentHeadFreq, arr, arr.length, lexicon);
            }

        } finally {
            for (PairRunIO.RunCursor c : cursors) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }

        return new long[] { droppedMissingHead, droppedMissingColl };
    }

    private boolean isLemmaIndexed(int lemmaId, LemmaLexiconReader lexicon, IndexReader reader, byte[] cache) throws IOException {
        if (lemmaId < 0 || lemmaId >= cache.length) {
            return false;
        }

        byte state = cache[lemmaId];
        if (state == 1) return true;
        if (state == 2) return false;

        String lemma = lexicon.getLemma(lemmaId);
        boolean present = lemma != null && !lemma.isEmpty() && reader.docFreq(new Term("lemma", lemma)) > 0;
        cache[lemmaId] = present ? (byte) 1 : (byte) 2;
        return present;
    }

    private double calculateLogDice(long cooccurrence, long freq1, long freq2) {
        if (cooccurrence <= 0 || freq1 <= 0 || freq2 <= 0) {
            return 0.0;
        }
        double dice = (2.0 * cooccurrence) / (freq1 + freq2);
        double logDice = Math.log(dice) / Math.log(2) + 14;
        return Math.max(0, Math.min(14, logDice));
    }

    /** Candidate stored during reduce. */
    public static final class Candidate {
        public final int collId;
        public final long cooc;
        public final long collFreq;
        public final float logDice;

        public Candidate(int collId, long cooc, long collFreq, float logDice) {
            this.collId = collId;
            this.cooc = cooc;
            this.collFreq = collFreq;
            this.logDice = logDice;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: CollocationsBuilderV2 <indexPath> <outputPath> [options]");
            System.err.println("Options:");
            System.err.println("  --window N        Window size (default: 5)");
            System.err.println("  --top-k N         Top-K collocates (default: 100)");
            System.err.println("  --min-freq N      Minimum headword frequency (default: 10)");
            System.err.println("  --min-cooc N      Minimum cooccurrence (default: 2)");
            System.err.println("  --shards N        Number of shards (power of 2, default: 64)");
            System.err.println("  --spill N         Spill threshold per shard (default: 2000000)");
            System.exit(1);
        }

        String indexPath = args[0];
        String outputPath = args[1];

        CollocationsBuilderV2 b = new CollocationsBuilderV2(indexPath);

        for (int i = 2; i < args.length; i += 2) {
            if (i + 1 >= args.length) break;
            String opt = args[i];
            String val = args[i + 1];
            switch (opt) {
                case "--window" -> b.setWindowSize(Integer.parseInt(val));
                case "--top-k" -> b.setTopK(Integer.parseInt(val));
                case "--min-freq" -> b.setMinHeadwordFrequency(Integer.parseInt(val));
                case "--min-cooc" -> b.setMinCooccurrence(Integer.parseInt(val));
                case "--shards" -> b.setNumShards(Integer.parseInt(val));
                case "--spill" -> b.setSpillThresholdPerShard(Integer.parseInt(val));
            }
        }

        b.build(outputPath);
    }
}

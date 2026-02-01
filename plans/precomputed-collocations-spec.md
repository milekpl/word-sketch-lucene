# Precomputed Collocations: Specification and Implementation Plan

## Executive Summary

Replace runtime collocation computation (40-100s per query) with precomputed lookup (<100ms per query) by building a collocations index during corpus indexing.

**Expected speedup: 1000x**

---

## 1. Problem Statement

### Current Performance (SAMPLE_SCAN)

| Headword Frequency | Query Time | Bottleneck |
|-------------------|------------|------------|
| High ("the": 39M) | 95 sec | Token scanning in 5000 sampled sentences |
| Medium ("make": 1.5M) | 51 sec | Same |
| Low ("demonstrate": 24K) | 105 sec | Same (sample ratio affects accuracy) |

### Root Cause

Every query:
1. Samples N sentences containing headword
2. Loads token sequences from DocValues
3. Scans all tokens for cooccurrences in window
4. Computes logDice for all found collocates

This is **O(S × L)** per query where S = sample size, L = avg tokens per sentence.

### Solution

Precompute collocations for ALL lemmas at index time. Store top-K collocates per lemma. Query becomes O(1) hash lookup.

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                     INDEXING PIPELINE                           │
├─────────────────────────────────────────────────────────────────┤
│  Corpus → HybridIndexer → index-hybrid/                         │
│                              ├── segments_*                     │
│                              ├── stats.bin (term frequencies)   │
│                              └── [NEW] collocations.bin         │
│                                                                 │
│  CollocationsBuilder (post-process):                            │
│    - Reads existing index + stats.bin                           │
│    - For each lemma: compute top-100 collocates by logDice      │
│    - Writes collocations.bin                                    │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                      QUERY PIPELINE                             │
├─────────────────────────────────────────────────────────────────┤
│  HybridQueryExecutor:                                           │
│    - Algorithm.PRECOMPUTED: Read from CollocationsReader        │
│    - Fallback to SAMPLE_SCAN for CQL pattern filtering          │
│                                                                 │
│  CollocationsReader:                                            │
│    - Memory-mapped collocations.bin                             │
│    - O(1) lookup by lemma                                       │
│    - Returns List<Collocation> with logDice, frequency, POS     │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Data Structures

### 3.1 Collocation Record

```java
public record Collocation(
    String collocateLemma,      // The collocating word
    String collocatePOS,        // Most frequent POS tag
    long cooccurrenceCount,     // Raw cooccurrence count
    long collocateFrequency,    // Total corpus frequency
    double logDice              // Association score
) {}
```

### 3.2 Collocation Entry (per headword)

```java
public record CollocationEntry(
    String headwordLemma,
    long headwordFrequency,
    int windowSize,             // Distance used (e.g., 5)
    List<Collocation> collocates  // Top-K by logDice, sorted descending
) {}
```

### 3.3 Binary File Format

```
collocations.bin:
┌────────────────────────────────────────────────────────────────┐
│ HEADER (32 bytes)                                              │
├────────────────────────────────────────────────────────────────┤
│ magic: int32 = 0x434F4C4C ("COLL")                             │
│ version: int32 = 1                                             │
│ entryCount: int64                                              │
│ windowSize: int32                                              │
│ maxCollocatesPerEntry: int32                                   │
│ totalCorpusTokens: int64                                       │
├────────────────────────────────────────────────────────────────┤
│ INDEX SECTION (for O(1) lookup)                                │
├────────────────────────────────────────────────────────────────┤
│ offsetTableOffset: int64 (points to hash table)                │
│ offsetTableSize: int32                                         │
├────────────────────────────────────────────────────────────────┤
│ DATA SECTION (sequential entries)                              │
├────────────────────────────────────────────────────────────────┤
│ for each entry:                                                │
│   headwordLen: int16                                           │
│   headwordBytes: byte[headwordLen]                             │
│   headwordFreq: int64                                          │
│   collocateCount: int16                                        │
│   for each collocate:                                          │
│     lemmaLen: int8                                             │
│     lemmaBytes: byte[lemmaLen]                                 │
│     posLen: int8                                               │
│     posBytes: byte[posLen]                                     │
│     cooccurrence: int64                                        │
│     collocateFreq: int64                                       │
│     logDice: float32                                           │
├────────────────────────────────────────────────────────────────┤
│ OFFSET TABLE (hash-based index)                                │
├────────────────────────────────────────────────────────────────┤
│ bucketCount: int32                                             │
│ for each bucket:                                               │
│   entryOffset: int64 (or -1 if empty)                          │
│   nextInChain: int64 (for collision handling)                  │
└────────────────────────────────────────────────────────────────┘
```

### 3.4 Size Estimation

For 4.4M lemmas × 100 collocates × ~50 bytes/collocate:

| Component | Size |
|-----------|------|
| Header | 32 bytes |
| Entries | 4.4M × (20 + 100 × 50) ≈ 22 GB |
| Offset table | 4.4M × 16 ≈ 70 MB |
| **Total** | **~22 GB** |

**Optimization**: Store only lemmas with freq ≥ 10, reduce to top-50:
- 1M lemmas × 50 collocates × 50 bytes ≈ **2.5 GB**

---

## 4. Implementation Plan

### Phase 1: Core Data Structures (Day 1)

#### Task 1.1: Create Collocation record classes

**File**: `src/main/java/pl/marcinmilkowski/word_sketch/indexer/hybrid/Collocation.java`

```java
package pl.marcinmilkowski.word_sketch.indexer.hybrid;

public record Collocation(
    String lemma,
    String pos,
    long cooccurrence,
    long frequency,
    double logDice
) implements Comparable<Collocation> {
    @Override
    public int compareTo(Collocation other) {
        return Double.compare(other.logDice, this.logDice); // Descending
    }
}
```

#### Task 1.2: Create CollocationEntry class

**File**: `src/main/java/pl/marcinmilkowski/word_sketch/indexer/hybrid/CollocationEntry.java`

```java
package pl.marcinmilkowski.word_sketch.indexer.hybrid;

import java.util.List;

public record CollocationEntry(
    String headword,
    long headwordFrequency,
    List<Collocation> collocates
) {}
```

---

### Phase 2: CollocationsBuilder (Day 2-3)

#### Task 2.1: Core builder class

**File**: `src/main/java/pl/marcinmilkowski/word_sketch/indexer/hybrid/CollocationsBuilder.java`

**Algorithm**:

```
1. Load stats.bin → get all lemmas with freq ≥ minFrequency
2. For each lemma L (with progress tracking):
   a. Query index for all docs containing L
   b. For each doc:
      - Load tokens from DocValues
      - Find all positions of L
      - For each position, collect collocates within window
   c. Aggregate cooccurrence counts
   d. Compute logDice for each collocate
   e. Sort by logDice, keep top-K
   f. Write entry to output buffer
3. Build hash index for O(1) lookup
4. Write collocations.bin
```

**Key optimizations**:
- Process in batches to manage memory
- Use parallel streams for independent lemmas
- Skip stopwords as headwords (optional)
- Checkpoint progress for resumability

#### Task 2.2: Binary writer

**Method**: `writeCollocationsBinary(Path outputPath)`

- Memory-mapped file for efficiency
- Two-pass: first pass computes offsets, second pass writes data

---

### Phase 3: CollocationsReader (Day 4)

#### Task 3.1: Reader class

**File**: `src/main/java/pl/marcinmilkowski/word_sketch/indexer/hybrid/CollocationsReader.java`

```java
public class CollocationsReader implements Closeable {
    private final MappedByteBuffer buffer;
    private final Map<String, Long> offsetIndex;  // lemma → file offset
    
    public CollocationsReader(String path) throws IOException;
    
    public CollocationEntry getCollocations(String lemma);
    
    public boolean hasLemma(String lemma);
    
    public int getWindowSize();
    
    public long getEntryCount();
}
```

**Lookup complexity**: O(1) hash lookup + O(K) deserialization where K = collocates per entry

---

### Phase 4: Integration with Query Executor (Day 5)

#### Task 4.1: Add PRECOMPUTED algorithm

**File**: `HybridQueryExecutor.java`

```java
public enum Algorithm {
    SAMPLE_SCAN,      // Original: sample sentences, scan tokens
    SPAN_COUNT,       // Iterate candidates, count via SpanNear
    PRECOMPUTED       // NEW: Read from collocations.bin
}
```

#### Task 4.2: Implement precomputed query path

```java
private List<WordSketchResult> findCollocationsPrecomputed(
        String headword, String cqlPattern, double minLogDice, int maxResults) 
        throws IOException {
    
    if (collocationsReader == null) {
        logger.warn("PRECOMPUTED: collocations.bin not available, falling back");
        return findCollocationsSampleScan(headword, cqlPattern, minLogDice, maxResults);
    }
    
    CollocationEntry entry = collocationsReader.getCollocations(headword);
    if (entry == null) {
        return Collections.emptyList();
    }
    
    // Filter by CQL pattern if provided
    List<Collocation> filtered = entry.collocates();
    if (cqlPattern != null && !cqlPattern.isBlank()) {
        CQLPattern pattern = parser.parse(cqlPattern);
        filtered = filterByPattern(filtered, pattern);
    }
    
    // Filter by minLogDice
    filtered = filtered.stream()
        .filter(c -> c.logDice() >= minLogDice)
        .limit(maxResults)
        .toList();
    
    // Convert to WordSketchResult (examples not available in precomputed mode)
    return filtered.stream()
        .map(c -> new WordSketchResult(
            c.lemma(), c.pos(), c.cooccurrence(), c.logDice(), 0.0, 
            Collections.emptyList()))
        .toList();
}
```

#### Task 4.3: Lazy example loading

For UI that needs examples, add separate method:

```java
public List<String> getExamplesForCollocation(String headword, String collocate, int limit) {
    // Use SpanNear query to find example sentences
    SpanNearQuery query = buildSpanNear(headword, collocate, windowSize);
    TopDocs docs = searcher.search(query, limit);
    return extractSentenceTexts(docs);
}
```

---

### Phase 5: CLI and Scripts (Day 6)

#### Task 5.1: CollocationsBuilder CLI

**File**: `CollocationsBuilder.java` main method

```bash
java -jar word-sketch.jar build-collocations \
    --index d:\corpus_74m\index-hybrid \
    --output d:\corpus_74m\index-hybrid\collocations.bin \
    --window 5 \
    --top-k 100 \
    --min-frequency 10 \
    --threads 8
```

#### Task 5.2: PowerShell script

**File**: `build_collocations.ps1`

```powershell
param(
    [string]$IndexPath = "d:\corpus_74m\index-hybrid",
    [int]$Window = 5,
    [int]$TopK = 100,
    [int]$MinFreq = 10
)

$jar = "target\word-sketch-lucene-1.0-SNAPSHOT-jar-with-dependencies.jar"
java -Xmx16g -jar $jar build-collocations `
    --index $IndexPath `
    --output "$IndexPath\collocations.bin" `
    --window $Window `
    --top-k $TopK `
    --min-frequency $MinFreq `
    --threads ([Environment]::ProcessorCount)
```

---

### Phase 6: Testing and Benchmarking (Day 7)

#### Task 6.1: Unit tests

**File**: `CollocationsBuilderTest.java`
- Test binary format read/write roundtrip
- Test hash index correctness
- Test logDice calculation

#### Task 6.2: Integration test

**File**: `CollocationsIntegrationTest.java`
- Build collocations for test corpus
- Query and verify results match SAMPLE_SCAN

#### Task 6.3: Performance benchmark

**File**: `AlgorithmPerformanceTest.java` (update)

Add PRECOMPUTED to the benchmark suite.

---

## 5. API Changes

### 5.1 New Endpoint: Get Algorithm Info

```
GET /api/algorithm
Response:
{
  "algorithm": "PRECOMPUTED",
  "available": ["SAMPLE_SCAN", "SPAN_COUNT", "PRECOMPUTED"],
  "collocations_loaded": true,
  "collocations_entries": 1234567,
  "collocations_window": 5
}
```

### 5.2 Lazy Example Loading

```
GET /api/sketch/{lemma}/examples?collocate={collocate}&limit=5
Response:
{
  "headword": "problem",
  "collocate": "solve",
  "examples": [
    "We need to solve this problem quickly.",
    "The team solved the problem by...",
    ...
  ]
}
```

---

## 6. Configuration

### 6.1 Builder Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `--window` | 5 | Cooccurrence window size (tokens) |
| `--top-k` | 100 | Max collocates per headword |
| `--min-frequency` | 10 | Skip headwords with freq < this |
| `--min-cooccurrence` | 2 | Skip collocates with cooc < this |
| `--threads` | CPU cores | Parallel processing threads |
| `--batch-size` | 10000 | Lemmas per batch |

### 6.2 Runtime Configuration

```java
// In HybridQueryExecutor constructor
if (Files.exists(Paths.get(indexPath, "collocations.bin"))) {
    collocationsReader = new CollocationsReader(indexPath + "/collocations.bin");
    algorithm = Algorithm.PRECOMPUTED;
    logger.info("Loaded precomputed collocations: {} entries", 
        collocationsReader.getEntryCount());
}
```

---

## 7. Rollout Plan

### Week 1: Implementation
- Days 1-3: Core builder + binary format
- Days 4-5: Reader + executor integration
- Days 6-7: Testing + benchmarking

### Week 2: Deployment
- Build collocations.bin for full index (~4-8 hours)
- Validate results against SAMPLE_SCAN
- Deploy to production
- Monitor query latencies

---

## 8. Success Criteria

| Metric | Current | Target |
|--------|---------|--------|
| Query latency (high-freq) | 95 sec | < 100 ms |
| Query latency (low-freq) | 105 sec | < 100 ms |
| Index size | 32 GB | < 40 GB |
| Build time | N/A | < 8 hours |
| Result accuracy | baseline | ≥ 95% overlap with SAMPLE_SCAN top-20 |

---

## 9. Future Enhancements

1. **Grammatical relation filtering**: Store collocates by relation type (object, modifier, etc.)
2. **Incremental updates**: Add new documents without full rebuild
3. **Compression**: LZ4 for collocations.bin to reduce size
4. **Distributed building**: Split lemmas across multiple machines
5. **POS-specific collocations**: Separate entries for noun/verb/adj senses

---

## 10. File Manifest

| File | Description |
|------|-------------|
| `Collocation.java` | Collocation record |
| `CollocationEntry.java` | Entry for one headword |
| `CollocationsBuilder.java` | Builds collocations.bin from index |
| `CollocationsReader.java` | Reads collocations.bin at query time |
| `CollocationsBuilderTest.java` | Unit tests |
| `CollocationsIntegrationTest.java` | Integration tests |
| `build_collocations.ps1` | Build script |


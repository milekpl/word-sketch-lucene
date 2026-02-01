# Precomputed Collocations - Quick Start

## Build Collocations Index

### Option 1: PowerShell Script (Recommended)
```powershell
# Install and build JAR
mvn package -DskipTests

# Build collocations for your index
.\build_collocations.ps1 -IndexPath target/index-74m
```

### Option 2: Direct Java Command
```bash
java -Xmx8G -cp target/word-sketch-lucene-1.0.0.jar \
  pl.marcinmilkowski.word_sketch.indexer.hybrid.CollocationsBuilder \
  target/index-74m \
  target/index-74m/stats.bin \
  target/index-74m/collocations.bin \
  --window 5 --top-k 100 --min-freq 10 --threads 8
```

## Use Precomputed Queries

```bash
# 1. Start server
java -jar target/word-sketch-lucene-1.0.0.jar --index target/index-74m --port 8080

# 2. Switch to PRECOMPUTED algorithm
curl -X POST http://localhost:8080/api/algorithm -d "PRECOMPUTED"

# 3. Query (now <100ms instead of 40-100s!)
curl "http://localhost:8080/api/collocations?headword=house&pattern=%5Blemma%3D%22.%2A%22%5D&minLogDice=7.0&limit=20"
```

## Run Tests

```bash
# Unit tests (always pass)
mvn test -Dtest="CollocationTest,CollocationEntryTest"

# Integration tests (requires index)
mvn test -Dtest="CollocationsBuilderTest,PrecomputedAlgorithmTest"
```

## Configuration Quick Reference

| Use Case | MinFrequency | TopK | WindowSize | Expected Time | File Size |
|----------|--------------|------|------------|---------------|-----------|
| Development | 100 | 50 | 5 | 5-10 min | 50-100 MB |
| Production | 10 | 100 | 5 | 4-8 hours | 2.5-5 GB |
| High Quality | 50 | 150 | 7 | 6-10 hours | 3-7 GB |

## Performance Comparison

| Algorithm | Query Time | Build Time | Storage | Use When |
|-----------|------------|------------|---------|----------|
| SAMPLE_SCAN | 40-100s | None | None | No precomputed data |
| SPAN_COUNT | 100-400s | None | None | DON'T USE (slower!) |
| **PRECOMPUTED** | **<100ms** | 4-8 hours | 2.5-5 GB | Production |

**Winner**: PRECOMPUTED (1000x speedup)

## Troubleshooting

**Build fails with OutOfMemoryError:**
```bash
# Increase heap size
java -Xmx16G -cp ...
```

**Queries still slow after build:**
```bash
# 1. Check algorithm is set
curl http://localhost:8080/api/algorithm

# 2. Verify collocations.bin exists
ls target/index-74m/collocations.bin

# 3. Restart server to reload
```

**File size too large:**
```powershell
# Increase MinFrequency to reduce lemmas
.\build_collocations.ps1 -IndexPath target/index-74m -MinFrequency 50

# Reduce TopK to limit collocates per headword
.\build_collocations.ps1 -IndexPath target/index-74m -TopK 50
```

## See Also

- [PRECOMPUTED_IMPLEMENTATION.md](PRECOMPUTED_IMPLEMENTATION.md) - Full technical documentation
- [plans/precomputed-collocations-spec.md](plans/precomputed-collocations-spec.md) - Original specification
- [AlgorithmPerformanceTest.java](src/test/java/pl/marcinmilkowski/word_sketch/query/AlgorithmPerformanceTest.java) - Performance benchmarks

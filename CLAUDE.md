# CLAUDE.md

This file documents the Word Sketch Lucene project for future development.

## Current Status

✅ **v1.0 Functional Release**

### What Works
- Corpus indexing with CoNLL-U format (POS tagging via UDPipe)
- Fast precomputed collocation lookups (O(1), ~0-1ms)
- CQL pattern matching with SpanQueries
- logDice scoring for collocation ranking
- 4 grammatical relations: ADJ_PREDICATE, ADJ_MODIFIER, SUBJECT_OF, OBJECT_OF
- REST API with semantic field exploration
- Web UI with D3.js force-directed graphs
- **Single-seed and multi-seed semantic field exploration**

### Limitations (See README.md)
- ❌ No agreement rules (no noun-adjective gender/number matching)
- ❌ Only 4 grammatical relations
- ❌ Limited morphological analysis
- ⚠️ Depends on input corpus quality

---

## Build & Deployment

### Prerequisites
```bash
Java 21+
Maven 3.6+
Python 3 (for web server)
UDPipe 2 (optional, for corpus tagging)
```

### Build
```bash
mvn clean package
```

### Deploy (3 services)
```bash
# Terminal 1: API Server
java -jar target/word-sketch-lucene-1.0.0.jar server --index d:\corpus_74m\index-hybrid --port 8080

# Terminal 2: Web UI (static server)
python -m http.server 3000 --directory webapp

# Terminal 3: Browser
# Open http://localhost:3000
```

### CLI Commands

See `src/main/java/pl/marcinmilkowski/word_sketch/Main.java`:

```bash
java -jar word-sketch-lucene.jar index --corpus text.txt --output data/index/
java -jar word-sketch-lucene.jar conllu --input corpus.conllu --output data/index/
java -jar word-sketch-lucene.jar query --index data/index/ --lemma house
java -jar word-sketch-lucene.jar server --index data/index/ --port 8080
```

---

## API Endpoints

### REST API (Port 8080)

| Endpoint | Purpose |
|----------|---------|
| `GET /health` | Server health |
| `GET /api/sketch/{lemma}` | Word sketch for lemma |
| `GET /api/semantic-field/explore` | Single-seed exploration |
| `GET /api/semantic-field/explore-multi` | **Multi-seed exploration (NEW)** |

### Multi-Seed Endpoint

```bash
curl "http://localhost:8080/api/semantic-field/explore-multi?seeds=theory,model,hypothesis&relation=adj_predicate&top=10"
```

Requires:
- `seeds`: Comma-separated nouns (min 2)
- `relation`: adj_predicate, adj_modifier, subject_of, object_of
- `top`, `min_logdice`, `min_shared`: Optional parameters

---

## Code Structure

```
src/main/java/pl/marcinmilkowski/word_sketch/
├── Main.java                           # CLI entry
├── api/
│   └── WordSketchApiServer.java        # REST API server
├── grammar/
│   ├── CQLParser.java                  # CQL parsing
│   └── CQLPattern.java                 # Pattern repr.
├── indexer/
│   ├── HybridLuceneIndexer.java        # Indexing
│   └── hybrid/
│       ├── CollocationsBuilderV2.java  # Precomputed building
│       └── HybridIndex.java            # Index wrapper
├── query/
│   ├── CQLToLuceneCompiler.java        # CQL -> Lucene
│   ├── HybridQueryExecutor.java        # Query exec.
│   ├── SemanticFieldExplorer.java      # Single-seed (bootstrap)
│   ├── SnowballCollocations.java       # Multi-seed
│   └── WordSketchQueryExecutor.java    # Legacy (fallback)
├── tagging/
│   ├── SimpleTagger.java               # Rule-based
│   └── ConllUProcessor.java            # CoNLL-U parsing
└── utils/
    └── LogDiceCalculator.java          # logDice scoring
```

---

## Key Implementation Details

### Grammatical Relations

| Relation | Pattern | Example |
|----------|---------|---------|
| ADJ_PREDICATE | X is ADJ | "theory is correct" |
| ADJ_MODIFIER | ADJ X | "correct theory" |
| SUBJECT_OF | X VERBs | "theory suggests" |
| OBJECT_OF | VERB X | "develop theory" |

### Multi-Seed Exploration (SemanticFieldExplorer.java + SnowballCollocations.java)

1. For each seed, find collocates using specified relation
2. Calculate intersection (common collocates)
3. Return all edges with sources/targets for visualization

### Precomputed Collocations

- Built during `mvn package` automatically
- File: `collocations.bin` (700MB for 74M sentences)
- Format: Spill-to-disk single-pass algorithm
- Performance: O(1) lookup, ~0-1ms per query

### logDice Scoring

```
logDice = log₂(2 * f(A,B) / (f(A) + f(B))) + 14
```

Range: 0-14 (14 = perfect association)

---

## Testing

```bash
# All tests
mvn test

# Specific test class
mvn test -Dtest=CQLParserTest

# With output
mvn test -X
```

Test coverage:
- CQL parsing (50+ patterns)
- Lucene query compilation
- logDice calculation
- API endpoints
- Multi-seed exploration

---

## Development Notes

### Common Debugging

**Index location:** `d:\corpus_74m\index-hybrid/`
- `segments.gen`, `segments_*` - Lucene index
- `stats.bin` - Frequency statistics
- `collocations.bin` - Precomputed collocations
- `lexicon.bin` - Lemma-to-ID mapping (fallback)

**Server logs:** Run with `--debug` or check console output

**API testing:** Use curl or Postman

### Performance Tuning

- Max heap: `-Xmx2g` in MAVEN_OPTS for large builds
- Worker threads: `NUM_WORKERS` in CollocationsBuilderV2
- Batch size: `BATCH_SIZE` in HybridLuceneIndexer

---

## For v2.0

Planned improvements:
- ✅ Multi-seed exploration
- ❌ Agreement rules (noun-adj matching)
- ❌ Additional relations (possessive, comparative)
- ❌ Morphological decomposition
- ❌ Word sense disambiguation

---

## Documentation

- **README.md** - User guide (indexing, querying, API, examples)
- **MULTI_SEED_EXPLORATION.md** - Multi-seed feature details
- **plans/word-sketch-lucene-spec.md** - Technical specification
- **plans/precomputed-collocations-spec.md** - Algorithm details
- **plans/hybrid-index-spec.md** - Index architecture
Main Corpus Index          Word Sketch Index
- sentence_id              - sentence_id
- position                 - position
- word                     - word
- lemma                    - lemma (indexed)
- (NO tag)                 - tag (indexed)
- sentence                 - sentence
                           - pos_group
Fast KWIC/concordance      Fast pattern matching
```

The **Main Corpus Index** is for keyword search (5-20ms queries). The **Word Sketch Index** stores POS tags for pattern matching (50-200ms queries).

### Index Schema

| Field | Type | Purpose |
|-------|------|---------|
| `doc_id` | Numeric, stored | Sentence ID for example retrieval |
| `position` | Numeric, stored | Word position in sentence |
| `word` | Stored, not tokenized | Raw word form for display |
| `lemma` | Analyzed, indexed | Lemmatized form for search |
| `tag` | Keyword, indexed | POS tag (e.g., "NN", "VBD") |
| `pos_group` | Keyword, indexed | Broad category: noun, verb, adj, adv |
| `sentence` | Stored | Full sentence for KWIC display |
| `start_offset`, `end_offset` | Numeric, stored | Character offsets for highlighting |

### CQL to Lucene Translation

| CQL Construct | Example | Lucene Equivalent |
|--------------|---------|-------------------|
| Labeled position | `1:"N.*"` | `SpanFirstQuery(TermQuery(tag="N.*"), 0)` |
| Constraint | `[tag="adj"]` | `TermQuery(tag=re.compile("adj.*"))` |
| Negation | `[tag!="N.*"]` | `BooleanQuery(MUST_NOT + ...)` |
| Distance `{min,max}` | `{0,2}` | `SpanNearQuery(..., slop=max, inOrder=...)` |
| Sequence | `A B C` | `SpanNearQuery([A, B, C], slop=0, inOrder=true)` |

### logDice Scoring Formula

```
logDice = log2(2 * f(AB) / (f(A) + f(B))) + 14
```

Where `f(AB)` = collocate frequency, `f(A)` = headword frequency, `f(B)` = collocate total frequency.

## Key Components

- **[Main.java](src/main/java/pl/marcinmilkowski/word_sketch/Main.java)**: CLI entry point
- **[LuceneIndexer.java](src/main/java/pl/marcinmilkowski/word_sketch/indexer/LuceneIndexer.java)**: Index creation with 256MB RAM buffer
- **[grammar/CQLParser.java](src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLParser.java)**: CQL pattern parser
- **[query/CQLToLuceneCompiler.java](src/main/java/pl/marcinmilkowski/word_sketch/query/CQLToLuceneCompiler.java)**: Compiles CQL to Lucene SpanQueries
- **[query/WordSketchQueryExecutor.java](src/main/java/pl/marcinmilkowski/word_sketch/query/WordSketchQueryExecutor.java)**: Query executor with logDice scoring
- **[tagging/SimpleTagger.java](src/main/java/pl/marcinmilkowski/word_sketch/tagging/SimpleTagger.java)**: Rule-based POS tagger
- **[tagging/UDPipeTagger.java](src/main/java/pl/marcinmilkowski/word_sketch/tagging/UDPipeTagger.java)**: UDPipe 2 integration for POS tagging
- **[api/WordSketchApiServer.java](src/main/java/pl/marcinmilkowski/word_sketch/api/WordSketchApiServer.java)**: REST API endpoints
- **[sketchgrammar.wsdef.m4](sketchgrammar.wsdef.m4)**: English Penn Treebank 3.3 grammar in m4 macro format

## POS Tagging Pipeline

**Recommended: UDPipe 2** - Fast, supports 50+ languages, outputs CoNLL-U format. See [migration plan](word-sketch-lucene-migration.md) for integration details.

## Dependencies

- Java 21+ (Lucene 10.3.2 requires Java 21+)
- Apache Lucene 10.3.2
- Fastjson 2.0.25
- SLF4J 2.0.7
- JUnit 5.9.0

# Word Sketch Lucene

A high-performance corpus-based collocation analysis tool built on Apache Lucene. This project implements word sketch functionality (grammatical relations and collocations) for corpus linguistics research and NLP applications.

**Current Status:** ✅ **Functional Release v1.0** - See [Limitations](#limitations) section.

## Features

- **Fast Collocation Analysis**: O(1) instant lookup with precomputed collocations
- **CQL Support**: Full Corpus Query Language with distance modifiers and constraints
- **logDice Scoring**: Association strength metric (0-14 scale)
- **Multiple Grammatical Relations**: ADJ_PREDICATE, ADJ_MODIFIER, SUBJECT_OF, OBJECT_OF
- **Concordance Examples**: View real corpus sentences for any word pair with highlighting
- **REST API**: HTTP server with semantic field exploration endpoints
- **Web Interface**: Interactive Semantic Field Explorer with D3.js visualization
- **Multi-Seed Exploration**: Explore semantic fields using multiple seed words

## Quick Start (5 minutes)

### Prerequisites

- Java 17+ (Java 21+ recommended)
- Maven 3.6+
- Python 3 (for web server)

### 1. Build

```bash
mvn clean package
```

### 2. Download Example Index

A precomputed 74M-sentence English corpus index is available in `d:\corpus_74m\index-hybrid/` (already set up if you're reading this).

Or create a small index:
```bash
# Tag corpus with UDPipe
udpipe --tokenize --tag --lemma --output=conllu english-model.udpipe corpus.txt > corpus.conllu

# Index the CoNLL-U file
java -jar target/word-sketch-lucene-1.0.0.jar conllu --input corpus.conllu --output data/index/
```

### 3. Start API Server

```bash
# Terminal 1
java -jar target/word-sketch-lucene-1.0.0.jar server --index data/index/ --port 8080
```

Server startup output:
```
API server started on http://localhost:8080
Algorithm: PRECOMPUTED (O(1) instant lookup)
Endpoints:
  GET /health
  GET /api/sketch/{lemma}
  GET /api/semantic-field/explore
  GET /api/semantic-field/explore-multi
```

### 4. Start Web Interface

```bash
# Terminal 2
python -m http.server 3000 --directory webapp
```

Open browser to: **http://localhost:3000**

### 5. Try a Query

```bash
# Find adjectives describing "house"
curl "http://localhost:8080/api/sketch/house?pos=noun"

# Get example sentences for "house" + "big"
curl "http://localhost:8080/api/concordance/examples?word1=house&word2=big&limit=5"

# Explore semantic field from "theory" (ADJ_PREDICATE relation)
curl "http://localhost:8080/api/semantic-field/explore?seed=theory&relation=adj_predicate"

# Multi-seed exploration
curl "http://localhost:8080/api/semantic-field/explore-multi?seeds=theory,model,hypothesis&top=10"
```

---

## Core Usage

### Index a Corpus

#### From CoNLL-U (Recommended)

```bash
# 1. Tag with UDPipe (creates CoNLL-U format)
udpipe --tokenize --tag --lemma --output=conllu english-model.udpipe corpus.txt > corpus.conllu

# 2. Index the CoNLL-U file
java -jar word-sketch-lucene.jar conllu --input corpus.conllu --output data/index/

# Optional: Build precomputed collocations for instant lookups (can take hours on large corpora)
java -jar word-sketch-lucene.jar precomputed --index data/index/
```

#### From Raw Text

```bash
# Uses simple rule-based POS tagger (limited coverage)
java -jar word-sketch-lucene.jar index --corpus sentences.txt --output data/index/
```

### Query via Command Line

```bash
# Get all collocations for "house"
java -jar word-sketch-lucene.jar query --index data/index/ --lemma house

# Find adjectives modifying "problem"
java -jar word-sketch-lucene.jar query --index data/index/ --lemma problem \
  --pattern "[tag=\"jj.*\"]" --limit 20

# Find what "theory" is object of
java -jar word-sketch-lucene.jar query --index data/index/ --lemma theory \
  --pattern "[tag=\"vb.*\"]" --limit 20
```

### REST API Endpoints

#### Health Check
```bash
curl http://localhost:8080/health
```

#### Get Word Sketch
```bash
curl "http://localhost:8080/api/sketch/house?pos=noun,verb&limit=15"
```

Response:
```json
{
  "status": "ok",
  "lemma": "house",
  "patterns": {
    "noun_modifiers": {
      "name": "Adjectives modifying (ADJ X)",
      "cql": "[tag=jj.*]~{0,3}",
      "total_matches": 3421,
      "collocations": [
        {
          "lemma": "big",
          "frequency": 287,
          "logDice": 11.24,
          "relativeFrequency": 0.084
        }
      ]
    }
  }
}
```

#### Single-Seed Semantic Field Exploration
```bash
curl "http://localhost:8080/api/semantic-field/explore?seed=theory&relation=adj_predicate&top=15&min_logdice=2"
```

**Relations:**
- `adj_predicate`: "X is ADJ" (e.g., "theory is correct")
- `adj_modifier`: "ADJ X" (e.g., "correct theory")  
- `subject_of`: "X VERBs" (e.g., "theory suggests")
- `object_of`: "VERB X" (e.g., "develop theory")

**Response:**
```json
{
  "status": "ok",
  "seed": "theory",
  "seed_collocates": [
    {"word": "correct", "logDice": 4.21, "frequency": 142},
    {"word": "practical", "logDice": 3.73, "frequency": 98}
  ],
  "discovered_nouns": [
    {
      "word": "development",
      "shared_count": 5,
      "shared_collocates": ["correct", "practical", "quantum"]
    }
  ]
}
```

#### Multi-Seed Semantic Field Exploration (NEW)
```bash
curl "http://localhost:8080/api/semantic-field/explore-multi?seeds=theory,model,hypothesis&relation=adj_predicate&top=10"
```

**Response:**
```json
{
  "status": "ok",
  "seeds": ["theory", "model", "hypothesis"],
  "seed_collocates_count": 23,
  "common_collocates": [],
  "common_collocates_count": 0,
  "edges": [
    {"source": "theory", "target": "correct", "weight": 4.21, "type": "ADJ_PREDICATE"},
    {"source": "model", "target": "tall", "weight": 3.17, "type": "ADJ_PREDICATE"}
  ]
}
```

#### Concordance Examples for Word Pairs
```bash
curl "http://localhost:8080/api/concordance/examples?word1=house&word2=big&limit=10"
```

Get actual example sentences from the corpus containing both words (lemmas). This feature validates collocations by showing real usage contexts.

**How It Works:**
1. Uses **SpanNearQuery** to efficiently find sentences where both lemmas appear within 10 words
2. Decodes token data (word, lemma, tag, position) from **BinaryDocValues** (`tokens` field)
3. Generates HTML with `<mark>` tags highlighting both target words
4. Returns sentence text, highlighted HTML, and position arrays

**Technical Details:**
- The HYBRID index stores tokens as BinaryDocValues, decoded via `TokenSequenceCodec`
- Lemma field is indexed with positions, enabling fast SpanQueries
- No need to store lemma/word/tag as separate StoredFields - DocValues provide O(1) lookup
- Query complexity: O(log N) for SpanQuery + O(k) for decoding k matching documents

**Parameters:**
- `word1` (required) - First word (lemma)
- `word2` (required) - Second word (lemma)
- `limit` (optional) - Number of examples to return (default: 10)
- `relation` (optional) - Grammatical relation metadata (not used in query)

**Response:**
```json
{
  "status": "ok",
  "count": 3,
  "word1": "house",
  "word2": "big",
  "relation": "",
  "limit_requested": 10,
  "examples": [
    {
      "sentence": "The big house! - The big house.",
      "highlighted": "The <mark>big</mark> <mark>house</mark> ! - The <mark>big</mark> <mark>house</mark> .",
      "raw": "The big house ! - The big house .",
      "word1_positions": [2, 7],
      "word2_positions": [1, 6]
    },
    {
      "sentence": "Houses Big and beautiful house with 4 bedrooms Houses big...",
      "highlighted": "<mark>Houses</mark> <mark>Big</mark> and beautiful <mark>house</mark> with 4 bedrooms <mark>Houses</mark> <mark>big</mark> ...",
      "raw": "Houses Big and beautiful house with 4 bedrooms Houses big ...",
      "word1_positions": [0, 4, 8],
      "word2_positions": [1, 9]
    }
  ]
}
```

**Response Fields:**
- `sentence` - Raw sentence text from the corpus
- `highlighted` - HTML with `<mark>` tags around matching words
- `raw` - Tokenized sentence (space-separated)
- `word1_positions` - Array of token positions where word1 appears (0-indexed)
- `word2_positions` - Array of token positions where word2 appears (0-indexed)

**Use Cases:**
- Validate collocations before citing in research
- Understand usage contexts and frequency patterns
- Discover idiomatic expressions and multi-word units
- Quality check corpus tagging and lemmatization

**Integration with Web UI:**
- **Word Sketch tab**: Click any collocation word to see inline examples
- **Semantic Field Explorer**: Click graph edges to see example sentences
- Examples appear in expandable panels below the visualization
- Up to 10 examples shown with "Load More" option for additional contexts

---

## Web Interface (Semantic Field Explorer)

The `webapp/` directory contains an interactive web interface built with D3.js.

### Features

1. **Word Sketch Search**
   - Browse collocations for any lemma
   - Filter by POS tags
   - **Click any collocation to see example sentences** from the corpus
   - Examples appear in a panel below with highlighted target words
   - Adjust logDice thresholds

2. **Single-Seed Exploration**
   - Bootstrap from one seed word
   - Select grammatical relation
   - Discover semantically similar words
   - Force-directed graph visualization

3. **Multi-Seed Exploration** (NEW)
   - Explore from multiple seeds at once
   - See all collocates per seed
   - Identify common patterns
   - Cluster-based semantic field analysis

### Start Both Services

```bash
# Terminal 1: API Server
java -jar target/word-sketch-lucene-1.0.0.jar server --index d:\corpus_74m\index-hybrid --port 8080

# Terminal 2: Web Server
python -m http.server 3000 --directory webapp

# Open browser to http://localhost:3000
```

---

## CQL Pattern Syntax

### Basic Patterns

| Pattern | Meaning |
|---------|---------|
| `"house"` | Match lemma "house" |
| `[tag="NN.*"]` | Match POS tag regex (nouns) |
| `[tag="JJ"]` | Match exact POS tag |
| `[word="the"]` | Match word form |

### Constraints

```cql
[tag="JJ.*"]              # Adjectives (any type)
[tag="VB.*"]              # Verbs (any type)
[tag="NN.*"]              # Nouns
[tag!="NN.*"]             # NOT nouns
[tag="JJ"|tag="RB"]       # Adjectives OR adverbs
```

### Distance Modifiers

```cql
[tag="JJ"]                # Adjacent (distance = 1)
[tag="JJ"] ~ {0,3}        # Within 0-3 words
[tag="JJ"] ~ {1,5}        # 1-5 words apart
```

### Examples

```cql
# Adjectives modifying a noun
[tag="jj.*"]

# Verbs taking noun as object
[tag="vb.*"]

# Adjectives within 3 words
[tag="jj.*"] ~ {0,3}
```

---

## Architecture

### Query Pipeline

```
User Input
    ↓
CQL Pattern Parser (CQLParser.java)
    ↓
Lucene SpanQuery Compiler (CQLToLuceneCompiler.java)
    ↓
Index Lookup (Lucene)
    ↓
logDice Scorer (LogDiceCalculator.java)
    ↓
Response (JSON/HTML)
```

### Index Structure

| Field | Type | Purpose |
|-------|------|---------|
| `doc_id` | Numeric, stored | Sentence ID |
| `position` | Numeric, stored | Word position |
| `word` | Stored | Raw word form |
| `lemma` | Indexed | Lemma for search |
| `tag` | Keyword, indexed | POS tag (NN, JJ, VB, etc.) |
| `pos_group` | Keyword | Broad category (noun/verb/adj/adv) |
| `sentence` | Stored | Full sentence |

### Collocation Computation

**logDice Formula:**
```
logDice = log₂(2 * f(A,B) / (f(A) + f(B))) + 14
```

Where:
- `f(A,B)` = co-occurrence frequency
- `f(A)` = headword frequency
- `f(B)` = collocate total frequency
- Scale: 0-14 (14 = perfect association)

---

## Project Structure

```
word-sketch-lucene/
├── src/main/java/pl/marcinmilkowski/word_sketch/
│   ├── Main.java                    # CLI entry point
│   ├── api/
│   │   └── WordSketchApiServer.java # REST API server
│   ├── grammar/
│   │   ├── CQLParser.java           # CQL pattern parsing
│   │   └── CQLPattern.java          # Pattern representation
│   ├── indexer/
│   │   ├── hybrid/
│   │   │   ├── HybridIndexer.java   # HYBRID index creation
│   │   │   ├── TokenSequenceCodec.java # Token encoding/decoding
│   │   │   ├── CollocationsBuilder.java # Precomputed collocations
│   │   │   └── SentenceDocument.java # Document model
│   │   └── LuceneIndexer.java       # Legacy indexer
│   ├── query/
│   │   ├── CQLToLuceneCompiler.java # CQL → Lucene translation
│   │   ├── HybridQueryExecutor.java # Query execution (PRECOMPUTED)
│   │   ├── ConcordanceExplorer.java # Concordance examples (SpanQuery)
│   │   ├── SemanticFieldExplorer.java # Single-seed exploration
│   │   ├── SnowballCollocations.java # Multi-seed exploration
│   │   └── WordSketchQueryExecutor.java # Legacy executor
│   ├── tagging/
│   │   ├── SimpleTagger.java        # Rule-based tagger
│   │   ├── ConllUProcessor.java     # CoNLL-U parsing
│   │   └── UDPipeTagger.java        # UDPipe wrapper
│   └── utils/
│       └── LogDiceCalculator.java   # logDice scoring
├── webapp/
│   ├── index.html                   # Web UI (D3.js visualization)
│   └── assets/                      # CSS, D3.js
├── src/test/java/                   # Unit tests
├── pom.xml                          # Maven config
└── README.md                        # This file
```

---

## Technical Deep Dive

### Concordance Examples Implementation

The concordance feature efficiently retrieves example sentences containing word pairs using a two-stage approach:

**Stage 1: SpanQuery for Fast Document Retrieval**
```java
// Build SpanNearQuery: both lemmas within 10 words
SpanTermQuery span1 = new SpanTermQuery(new Term("lemma", "house"));
SpanTermQuery span2 = new SpanTermQuery(new Term("lemma", "big"));

SpanNearQuery nearQuery = SpanNearQuery.newUnorderedNearQuery("lemma")
    .addClause(span1)
    .addClause(span2)
    .setSlop(10)  // Max distance: 10 tokens
    .build();

TopDocs results = searcher.search(nearQuery, limit);
```

**Stage 2: DocValues Decoding for Token Details**
```java
// For each matching document, decode tokens from BinaryDocValues
BinaryDocValues tokensDV = reader.getBinaryDocValues("tokens");
tokensDV.advanceExact(docId);
BytesRef tokensBytes = tokensDV.binaryValue();

// Decode using TokenSequenceCodec
List<Token> tokens = TokenSequenceCodec.decode(tokensBytes);

// Each token contains: position, word, lemma, tag, startOffset, endOffset
```

**Why This Design?**

1. **Compact Storage**: Tokens stored as binary (varint encoding) instead of separate fields
   - Typical sentence (~20 tokens): 400-600 bytes vs 1-2KB for separate fields
   - 62M sentence corpus: ~30GB vs ~80GB storage

2. **Fast Retrieval**: 
   - SpanQuery uses inverted index with positions → O(log N) lookup
   - DocValues provide O(1) document access (memory-mapped)
   - No need to reconstruct from stored text

3. **Position Accuracy**: 
   - Positions preserved from tagging pipeline
   - Support for multi-word tokens and contractions
   - Exact alignment with original text offsets

**Binary Encoding Format** (TokenSequenceCodec):
```
[token_count: varint]
For each token:
  [position: varint]
  [word_length: varint][word: UTF-8]
  [lemma_length: varint][lemma: UTF-8]
  [tag_length: varint][tag: UTF-8]
  [start_offset: varint]
  [end_offset: varint]
```

Varint encoding saves space for common cases (positions < 128 = 1 byte).

## Examples

### Example 1: Find Adjectives Describing "Theory"

```bash
curl "http://localhost:8080/api/semantic-field/explore?seed=theory&relation=adj_predicate&top=10"
```

**Result:**
```
correct (logDice: 4.21)
practical (logDice: 3.73)
wrong (logDice: 3.58)
mathematical (logDice: 3.47)
quantum (logDice: 2.89)
```

### Example 2: Find Words "House" Can Be Object Of

```bash
curl "http://localhost:8080/api/semantic-field/explore?seed=house&relation=object_of&top=10"
```

**Result:** Find verbs that take "house" as object
```
locate (logDice: 5.12)
build (logDice: 4.89)
buy (logDice: 4.21)
```

Discovered nouns (words that share these verbs):
```
hotel (shared: build, locate)
apartment (shared: build, buy, locate)
property (shared: buy, locate)
```

### Example 3: Multi-Seed Cluster Analysis

```bash
curl "http://localhost:8080/api/semantic-field/explore-multi?seeds=dog,cat,horse&relation=subject_of&top=8"
```

**Result:** What do dogs, cats, and horses do?
```
All seeds can: eat, run, live
Dog-specific: bark, beg, fetch
Cat-specific: meow, purr, scratch
```

---

## Performance

| Task | Time | Notes |
|------|------|-------|
| Index 74M sentences | ~2 hours | With UDPipe, parallelized |
| Query (PRECOMPUTED) | 0-1 ms | O(1) instant lookup |
| Query (on-the-fly) | 50-300 ms | Depends on word frequency |
| Web request | ~50 ms | Includes network latency |

---

## Limitations

This is a **functional v1.0 release** with the following limitations:

### Known Limitations

1. **No Agreement Rules**: Patterns don't enforce grammatical agreement
   - "big house" and "the big house" are treated the same
   - Consider this for interpretation of results

2. **Fixed Grammatical Relations**: Only 4 relation types implemented
   - `ADJ_PREDICATE`, `ADJ_MODIFIER`, `SUBJECT_OF`, `OBJECT_OF`
   - Custom patterns require CQL specification

3. **Limited Lemma Coverage**: Depends on the input corpus
   - Low-frequency words may have insufficient data
   - Compounds and rare morphological forms may not be covered

4. **No Morphological Analysis**: Simple POS tag matching
   - Plural/singular not distinguished
   - Verb tense merged into single lemma

### Planned for v2.0

- Agreement rules (noun-adjective gender/number matching)
- Additional grammatical relations (possessive, comparative, etc.)
- Morphological decomposition
- Word sense disambiguation
- Cross-lingual support

---

## Development

### Run Tests

```bash
mvn test
```

### Build Documentation

See `plans/` directory for:
- `word-sketch-lucene-spec.md` - Overall technical specification
- `precomputed-collocations-spec.md` - Precomputed algorithm details
- `hybrid-index-spec.md` - Hybrid index architecture

### Code Quality

Tests cover:
- CQL parsing (50+ patterns)
- Lucene query compilation
- logDice calculation
- API endpoints
- Multi-seed exploration

---

## API Documentation

# ConceptSketch Migration Technical Specification

## 1. Overview

This project aims to migrate the word-sketch system from the standalone Python implementation to Apache Lucene for significantly faster pattern matching on large corpora (up to 74M sentences). The system will provide efficient querying capabilities for collocation analysis using sketch grammars.

## 2. Architecture

### 2.1 Dual-Lucene Index Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│                    Lucene Cluster                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────────┐    ┌─────────────────────┐            │
│  │  Main Corpus Index  │    │  Word Sketch Index  │            │
│  │                     │    │                     │            │
│  │  - sentence_id      │    │  - sentence_id      │            │
│  │  - position         │    │  - position         │            │
│  │  - word             │    │  - word             │            │
│  │  - lemma            │    │  - lemma (indexed)  │            │
│  │  - (NO tag)         │    │  - tag (indexed)    │            │
│  │  - sentence         │    │  - sentence         │            │
│  │                     │    │  - pos_group        │            │
│  └─────────────────────┘    └─────────────────────┘            │
│          │                            │                          │
│  Fast KWIC/concordance        Fast pattern matching             │
│  ~5-20ms query               ~50-200ms query                    │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 Index Schema

**Word Sketch Index Fields:**
```python
{
    "doc_id": int,              # sentence ID (for example retrieval)
    "position": int,            # word position in sentence (0-indexed)
    "word": str,                # raw word form (stored, for display)
    "lemma": str,               # lemmatized form (indexed, analyzed)
    "tag": str,                 # POS tag (indexed, keyword)
    "pos_group": str,           # broad category: noun, verb, adj, adv (fast filter)
    "sentence": str,            # full sentence (stored, for KWIC)
    "start_offset": int,        # char offset in sentence (for highlighting)
    "end_offset": int
}
```

## 3. Key Components

### 3.1 CQL to Lucene Compiler

| CQL Construct | Example | Lucene Equivalent |
|--------------|---------|-------------------|
| Labeled position | `1:"N.*"` | `SpanFirstQuery(TermQuery(tag="N.*"), 0)` |
| Constraint | `[tag="adj"]` | `TermQuery(tag=re.compile("adj.*"))` |
| Word match | `[word="the"]` | `TermQuery(word="the")` |
| Negation | `[tag!="N.*"]` | `BooleanQuery(MUST_NOT + TermQuery(...))` |
| Distance {min,max} | `{0,2}` | `SpanNearQuery(..., slop=max, inOrder=...)` |
| OR | `\|` | `BooleanQuery(should=[...])` |
| Sequence | A B C | `SpanNearQuery([A, B, C], slop=0, inOrder=true)` |

### 3.2 POS Tagging Pipeline

**UDPipe 2 Integration:**
- Fast tagger with good accuracy (50+ languages)
- Pre-trained models available
- Outputs CoNLL-U format

**Flow:**
```
Raw Text → UDPipe Tokenization & Tagging → CoNLL-U → Lucene Index
```

### 3.3 Query Execution

**WordSketchCollector:**
- Collects lemma pairs during SpanQuery execution
- Handles frequency counting
- Applies agreement rules as post-filters
- Records examples for KWIC display

### 3.4 Scoring

**logDice Formula:**
```python
def compute_logdice(collocate_freq, headword_freq, collocate_total):
    dice = (2.0 * collocate_freq) / (headword_freq + collocate_total)
    logdice = math.log2(dice) + 14
    return max(0.0, logdice)
```

## 4. REST API

### 4.1 Endpoints

```
GET /sketch/{lemma}
GET /sketch/{lemma}?pos=noun,verb&min_logdice=5&limit=50

POST /sketch/query
{
    "lemma": "house",
    "patterns": [
        {"name": "modifiers", "cql": "1:noun 2:adj"},
        {"name": "subjects", "cql": "1:verb 2:noun[case=nom]"}
    ],
    "min_logdice": 5.0,
    "limit": 100
}
```

### 4.2 Response Format

```json
{
    "status": "ok",
    "lemma": "house",
    "total_headword_freq": 12458,
    "patterns": {
        "modifiers": {
            "cql": "1:noun 2:adj",
            "total_matches": 8932,
            "collocations": [
                {
                    "lemma": "big",
                    "pos": "adj",
                    "logDice": 11.24,
                    "freq": 1247,
                    "relative_freq": 0.10,
                    "examples": [
                        "big house",
                        "the big house",
                        "a very big house"
                    ]
                }
            ]
        }
    }
}
```

## 5. Grammar Support

### 5.1 English Grammar (Penn Treebank 3.3)

**Key Features:**
- 30+ collocation types
- Supports symmetric, dual, and trinary relations
- Covers modifiers, objects, subjects, complements, etc.
- Uses Penn Treebank tagset

**Example Patterns:**
```cql
# Noun modifiers
2:"(JJ.*|N.*[^Z])" [tag="JJ.*"|tag="RB.*"|word=","]{0,3} "N.*[^Z]"{0,2} 1:"N.*[^Z]" [tag!="N.*"]

# Verb objects
1:"V.*" "RB.*"{0,2} [tag="DT"|tag="PPZ"]{0,1} "CD"{0,2} [tag="JJ.*"|tag="RB.*"|word=","]{0,3} "N.*[^Z]"{0,2} 2:"N.*[^Z]" [tag!="N.*"]

# Parallel structures
1:"N.*[^Z]" [word=","]{0,1} [word="and"|word="or"|word=","] [tag="DT"|tag="PPZ"]{0,1} "CD"{0,2} [tag="JJ.*"|tag="RB.*"|word=","]{0,3} "N.*[^Z]"{0,2} 2:"N.*[^Z]" [tag!="N.*"]
```

### 5.2 Language Neutrality

- Tagset abstraction layer
- Support for multiple POS taggers (UDPipe, Stanza, spaCy)
- Grammar porting guidelines for other languages
- Configuration-driven language support

## 6. Performance Targets

| Metric | Target | Notes |
|--------|--------|-------|
| Index build time | <4 hours | With UDPipe, parallelized |
| Query latency (word sketch) | <200ms | For common lemmas |
| Query latency (custom pattern) | <500ms | Complex patterns |
| Index size | <60GB | With compression |
| Memory usage | <16GB | Query execution |

## 7. Project Structure

```
concept-sketch/
├── src/
│   ├── indexer/              # Lucene indexing functionality
│   ├── query/                # Query compilation and execution
│   ├── tagging/              # POS tagging pipeline
│   ├── api/                  # REST API endpoints
│   ├── grammar/              # Grammar parsing and compilation
│   └── utils/                # Utility functions (logDice, etc.)
├── tests/
│   ├── unit/                 # Unit tests
│   ├── integration/          # Integration tests
│   └── benchmarks/           # Performance benchmarks
├── grammars/
│   ├── english-penn_3.3.cql  # English grammar
│   └── polish-ipi_pan_1.1.cql # Polish grammar
├── docs/                     # Documentation
└── examples/                 # Usage examples
```

## 8. Implementation Plan

### Phase 1: Project Setup (Days 1-2)
- Create project directory structure
- Set up virtual environment
- Install dependencies (lucene, udpipe, flask, etc.)
- Create initial configuration files

### Phase 2: Lucene Indexing (Days 3-7)
- Implement Lucene indexer with POS tag support
- Create index schema
- Implement document writer and searcher
- Test indexing small corpus

### Phase 3: CQL to Lucene Translation (Days 8-14)
- Implement CQL parser
- Create SpanQuery builder for CQL patterns
- Support for labeled positions, constraints, word matches, negation, distance, OR, sequence
- Implement post-filter support for agreement rules
- Test pattern matching accuracy

### Phase 4: POS Tagging Pipeline (Days 15-18)
- Integrate UDPipe 2 for POS tagging
- Implement corpus tagging functionality
- Create CoNLL-U to Lucene format converter
- Test with small English and Polish corpora

### Phase 5: Query Execution (Days 19-24)
- Implement WordSketchCollector for frequency collection
- Create result aggregation and collocation extraction
- Implement logDice computation
- Optimize query performance
- Benchmark against original Python implementation

### Phase 6: API & Integration (Days 25-30)
- Create REST API endpoints
- Build Python client library
- Integrate with Flask application
- Write integration tests

### Phase 7: Grammar Porting & Testing (Days 31-40)
- Port English grammar rules (Penn Treebank 3.3)
- Create grammar-specific post-filter functions
- Test against original implementation for accuracy
- Performance tuning for 74M sentence corpus

### Phase 8: Deployment & Documentation (Days 41-45)
- Prepare deployment configuration
- Write usage documentation
- Create example notebooks
- Final performance testing and optimization

## 9. Dependencies

**Core:**
- Apache Lucene (for indexing and querying)
- UDPipe 2 (for POS tagging)
- Flask (for REST API)
- NumPy (for numerical computations)

**Development:**
- pytest (for testing)
- tqdm (for progress tracking)
- sphinx (for documentation)

## 10. Risks and Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| Large corpus processing time | Indexing delay | Use parallel processing, optimize tagger |
| Query performance | Slow responses | Pre-compute statistics, optimize SpanQueries |
| Grammar porting complexity | Inaccurate results | Create grammar validation tools, extensive testing |
| Memory usage | Out-of-memory errors | Lucene index optimization, query timeout handling |

## 11. Future Enhancements

- Support for additional POS taggers (Stanza, spaCy)
- Real-time query optimization
- Distributed indexing with SolrCloud
- Web-based query interface
- Advanced filtering and sorting options
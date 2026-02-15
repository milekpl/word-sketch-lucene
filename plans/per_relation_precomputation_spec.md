# Per-Relation Precomputation - Detailed Specification (v2)

## Problem Statement

Current approach has two modes:
1. **PRECOMPUTED**: O(1) lookup from precomputed collocations, but:
   - Returns ALL collocations (no relation-specific)
   - Must filter by POS at query time (approximate)
   - Cannot handle copula constraints efficiently

2. **SAMPLE_SCAN**: Full CQL pattern matching per query
   - Accurate but slow (~500ms per word)
   - CQL parsing not the bottleneck - token decoding and constraint matching are

Goal: O(1) query time with relation-specific precomputed collocations including copula handling.

## Critical Fixes from Review

### Fix 1: Binary Format - Exact Byte Layout
```
Offset table entry (per key):
  - Key length: 2 bytes (unsigned short, max 65535)
  - Key bytes: variable (UTF-8)
  - Offset: 8 bytes (long)
```
Using 2-byte (short) prefix for keys - sufficient for composite keys up to 64KB.

### Fix 2: Pattern-to-Collocate Mapping
For multi-token patterns, we define the "collocate" as:
- **Position 1** (labeled `1:` or first unlabeled): The headword
- **Position 2** (labeled `2:` or second unlabeled): The collocate

For trinary patterns like `prep_phrase: [tag=in] [tag=dt]? [tag=nn.*]`:
- The **last pattern element** is the collocate (the noun)
- Simplified approach: Use first element of pattern as collocate for now

### Fix 3: Copula Handling
For relations with `uses_copula: true`:
- Precompute by looking for copula lemma in window
- Only count collocates that appear WITH a copula in the sentence
- Store: (headword, relation, collocate, with_copula_count)

### Fix 4: Memory - Sparse Storage
Only store (headword, relation) pairs that have at least one collocation:
- NOT: All N×M combinations
- BUT: Only sparse entries where collocation count > 0
- Memory: ~same as current + small overhead for relation ID in key

### Fix 5: Version Coupling
Include in header:
- Corpus UUID: 16 bytes (generated at index time)
- Build timestamp: 8 bytes
- Both files must have matching UUID to be used together

---

## Architecture

### Storage Format

**Current**: `collocations.bin`
- Key: `lemma` (headword)
- Value: List of (collocate, POS, cooccurrence, logDice)

**New**: `relation_collocations.bin`
- Key: `lemma|relationId` (composite key)
- Value: List of (collocate, POS, cooccurrence, logDice)

### File Format (Binary) - EXACT SPEC

```
Header (total 48 bytes fixed):
  - MAGIC: 4 bytes "RLCL" (0x524C434C)
  - VERSION: 4 bytes (int) = 1
  - Entry count: 4 bytes (int) - number of data entries
  - Relations count: 4 bytes (int) - number of relations
  - Corpus UUID: 16 bytes (byte[16]) - unique corpus identifier
  - Build timestamp: 8 bytes (long) - millis since epoch
  - Window size: 4 bytes (int)
  - Top-K: 4 bytes (int)
  - Offset table offset: 8 bytes (long)

Relations section:
  - For each relation (count from header):
    - Length: 2 bytes (unsigned short)
    - UTF-8 bytes: relation ID string

Data entries (one per headword+relation that has collocations):
  - Key length: 2 bytes (unsigned short)
  - Key bytes: UTF-8 string "lemma|relationId"
  - Collocation count: 4 bytes
  - For each collocation:
    - Collocate length: 2 bytes (unsigned short)
    - Collocate lemma: UTF-8 string
    - POS length: 2 bytes (unsigned short)
    - POS tag: UTF-8 string (empty string if none)
    - Cooccurrence: 8 bytes (long)
    - LogDice: 4 bytes (float)

Offset table (sorted by key, binary searchable):
  - For each entry:
    - Key length: 2 bytes (unsigned short)
    - Key bytes: UTF-8 string
    - Offset: 8 bytes (long) - position in file where data entry starts
```

### New API

```java
// New class: RelationCollocationsReader
public class RelationCollocationsReader implements Closeable {
    // O(1) lookup by headword + relation
    CollocationEntry getCollocations(String headword, String relationId);

    // Check if a specific (headword, relation) has data
    boolean hasRelationData(String headword, String relationId);

    // Get corpus UUID for integrity checking
    String getCorpusUuid();
}

// In HybridQueryExecutor:
public List<WordSketchResult> findRelation(
    String headword,
    String relationId,
    double minLogDice,
    int maxResults
);
```

### Relations to Precompute

**Strategy: Start with POS-only patterns, no copula first**

Priority 1 (Simple POS - 5 relations):
- noun_modifiers: `[tag=jj.*]` - adjectives near nouns
- noun_compounds: `[tag=nn.*]` - nouns near nouns
- noun_verbs: `[tag=vb.*]` - verbs near nouns
- verb_nouns: `[tag=nn.*]` - nouns near verbs
- adj_nouns: `[tag=nn.*]` - nouns near adjectives

Priority 2 (Copula - 2 relations):
- noun_adj_predicates: `[tag=jj.*]` + copula check
- adj_verbs: `[tag=vb.*]` + copula check

Priority 3 (Complex - defer):
- Trinary patterns, wh-words, particles, etc.

### Implementation Changes

1. **CollocationsBuilder.java**
   - Add GrammarConfigLoader parameter with relation list
   - For each headword, track relation-specific collocations:
     - Parse relation's CQL pattern
     - Identify which pattern element is the "collocate" (last element)
     - Apply copula filter if uses_copula=true
   - Only write entries with collocations (sparse storage)

2. **New: RelationCollocationsReader.java**
   - Read new binary format
   - O(1) lookup with composite key
   - Binary search in offset table
   - Verify corpus UUID matches main collocations file

3. **HybridQueryExecutor.java**
   - Add RelationCollocationsReader field
   - Add `findRelation(headword, relationId, minLogDice, maxResults)` method
   - If precomputed available: O(1) lookup
   - Else: fallback to SAMPLE_SCAN

4. **Backward Compatibility**
   - Keep existing collocations.bin for general lookups
   - New file is optional - fall back to existing behavior if not present
   - If corpus UUIDs don't match, log warning and use fallback

### Performance Targets

| Metric | Current | Target |
|--------|---------|--------|
| Query time (per relation) | 100-500ms | <10ms |
| Memory (index) | 700MB | +100MB (sparse storage) |
| Build time | ~2 hours | +30% (estimated) |

### Edge Cases (Updated)

1. **Missing relation data**: Fall back to SAMPLE_SCAN
2. **Missing headword**: Return empty list
3. **Empty collocations**: Skip - don't write to file
4. **Invalid relation ID**: Throw IllegalArgumentException
5. **Version mismatch**: Log warning, use fallback
6. **Corpus UUID mismatch**: Log error, use fallback (critical for coherence)
7. **Sparse entries**: Only store headword|relation if collocations exist

## Acceptance Criteria

1. New binary format can be read by RelationCollocationsReader
2. O(1) lookup returns correct collocations for (headword, relation)
3. HybridQueryExecutor.findRelation() returns same results as SAMPLE_SCAN
4. Backward compatibility: works without new file
5. Tests pass for Priority 1 (POS-only) relations
6. Performance: <10ms per query for cached relations
7. Corpus UUID validation prevents incoherent results

## Simplified Approach for v1

Instead of full CQL pattern matching during precomputation:
1. Use simple POS-based patterns from grammar config
2. For copula relations: apply copula filter during precomputation
3. Skip trinary/complex patterns for now
4. Focus on the 7 exploration_enabled relations

This gives 80% of benefit with 20% of implementation complexity.

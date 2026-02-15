# Per-Relation Precomputation Plan

## Problem
- Current PRECOMPUTED gives all collocations with POS filter
- But copula-filtered relations (noun_adj_predicates) need special handling
- SAMPLE_SCAN is slow because it parses CQL at query time

## Solution
Precompute collocations **per relation** during indexing:

```
During indexing:
  For each sentence:
    For each token position:
      Identify which relations match at this position
      For each matching relation R:
        Aggregate: (headword_lemma, R, collocate_lemma) → count

  After all sentences:
    For each (headword, relation):
      Calculate logDice scores
      Store top-K

At query time:
  O(1) lookup: getCollocations(headword, relation_id)
```

## Architecture

### Storage Format
Add relation_id to the key. Two options:

**Option A: Single file with composite key** (recommended)
- Key format: `lemma|relation_id` (e.g., `theory|noun_modifiers`)
- Value: same as current (collocate list with logDice)

**Option B: Multiple files per relation**
- `collocations/noun_modifiers.bin`
- `collocations/noun_adj_predicates.bin`
- etc.

### Files to Modify

1. **CollocationsBuilder.java**
   - Accept grammar config with relation list
   - Track (headword, relation_id, collocate) triples
   - Compute logDice per (headword, relation) pair

2. **CollocationsReader.java**
   - Add `getCollocations(headword, relationId)` method
   - Support composite key lookup

3. **HybridQueryExecutor.java**
   - Use precomputed relation data when available
   - Fall back to SAMPLE_SCAN for non-precomputed relations

### Relations to Precompute
From the 41 relations, prioritize:

**High Priority (for exploration):**
- noun_modifiers (exploration_enabled)
- noun_adj_predicates (exploration_enabled, uses copula)
- noun_verbs (exploration_enabled)
- object_of (exploration_enabled)
- subject_of (exploration_enabled)
- verb_nouns (exploration_enabled)

**Medium Priority (common relations):**
- noun_compounds
- noun_prepositions
- verb_particles
- verb_prepositions

**Lower Priority (can use SAMPLE_SCAN):**
- Complex patterns (passive, reflexive, it_cleft)
- Rare relations

### Backward Compatibility
- Keep existing `collocations.bin` for general collocations
- New file: `relation_collocations.bin` (or similar)
- Fall back to general collocations + POS filter if relation-specific not available

## Implementation Steps

1. Modify CollocationsBuilder to accept relation list
2. Update storage format with composite key
3. Update CollocationsReader for new lookup
4. Update HybridQueryExecutor to use precomputed relations
5. Add relation_id to results for display

# Per-Relation Precomputation - Implementation Plan

## Phase 1: Core Infrastructure

### Step 1.1: New Binary Format (Day 1)
- [ ] Create `RelationCollocationsReader.java` class
  - Extend pattern from existing CollocationsReader
  - Support composite key lookup (lemma|relationId)
  - Add magic number "RLCL" for identification
  - Version: 1

### Step 1.2: Modify CollocationsBuilder (Day 1-2)
- [ ] Add GrammarConfigLoader parameter
- [ ] Add relation list configuration
- [ ] Modify buildForLemma to track relation-specific collocations
- [ ] Add new write method for relation-specific format

## Phase 2: Query Integration

### Step 2.1: HybridQueryExecutor Updates (Day 2)
- [ ] Add RelationCollocationsReader field
- [ ] Add `findRelation(headword, relationId, minLogDice, maxResults)` method
- [ ] Implement fallback to SAMPLE_SCAN when precomputed not available
- [ ] Add cache for parsed CQL patterns at startup

### Step 2.2: API Integration (Day 2)
- [ ] Update WordSketchApiServer to use new findRelation method
- [ ] Map relation IDs from grammar config to queries

## Phase 3: Testing & Validation

### Step 3.1: Unit Tests (Day 3)
- [ ] Test RelationCollocationsReader binary format
- [ ] Test O(1) lookup performance
- [ ] Test backward compatibility

### Step 3.2: Integration Tests (Day 3)
- [ ] Compare results with SAMPLE_SCAN for each relation
- [ ] Verify logDice scores match

## Phase 4: Build & Deployment

### Step 4.1: Rebuild Index (Day 4)
- [ ] Run CollocationsBuilder with new relation config
- [ ] Verify new binary file is created
- [ ] Test with real queries

### Step 4.2: Production Deployment (Day 4)
- [ ] Deploy new JAR
- [ ] Monitor performance

## Parallel Tasks

Can run in parallel:
- Step 1.1 + Step 1.2 (design together)
- Step 2.1 + Step 2.2 (coordinated)
- Step 3.1 + Step 3.2 (sequential - need implementation first)

## Risks & Mitigations

1. **Build time increase**: ~50% longer
   - Mitigate: Only precompute Priority 1 relations first

2. **Memory usage**: +200MB estimated
   - Mitigate: Use memory-mapped I/O, lazy loading

3. **Backward compatibility**: Must work without new file
   - Mitigate: Graceful fallback to SAMPLE_SCAN

4. **Relation matching complexity**: Some relations are complex
   - Mitigate: Start with simple POS-based relations

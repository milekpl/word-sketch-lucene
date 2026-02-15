# Detailed Implementation Plan: Phase A (Grammar-Driven CQL/Relation Config)

## 1. JSON Schema & Example
- Draft a JSON schema for relation definitions and copula sets.
- Example file: `d:/git/word-sketch-lucene/grammars/relations.json`

```json
{
  "relations": [
    {
      "name": "noun_adj_predicates",
      "label": "Adjective predicates",
      "head_pos": "NOUN",
      "collocate_pos": "ADJ",
      "cql": "[pos=NOUN] [lemma=be|seem|become] [pos=ADJ]",
      "copulas": ["be", "seem", "become"]
    }
    // ... more relations ...
  ],
  "copulas": ["be", "seem", "become"]
}
```

## 2. Java Backend Changes
### 2.1. Loader Class
- Create `src/main/java/pl/marcinmilkowski/word_sketch/config/GrammarConfigLoader.java`:
  - Loads and parses `grammars/relations.json` at startup.
  - Validates structure, logs errors, fails fast if invalid.
  - Provides accessors for relations and copula sets.

### 2.2. Data Model
- Create POJOs for Relation, CopulaSet, and GrammarConfig.
- Use Jackson or Gson for JSON parsing.

### 2.3. Integration
- Refactor `HybridQueryExecutor`, `WordSketchApiServer`, and `ConcordanceExplorer`:
  - Remove hardcoded relation tables and copula sets.
  - Use config-driven relations and copula sets everywhere.
  - Ensure noun_adj_predicates and all relations are loaded from config.

### 2.4. API Endpoint
- Add `/api/grammar/active` endpoint in `WordSketchApiServer`:
  - Returns the loaded grammar config as JSON.
  - Add error handling for missing/invalid config.

## 3. Testing
- Unit tests for loader (valid/invalid JSON, missing fields, etc).
- Endpoint test for `/api/grammar/active`.
- Manual test: edit `grammars/relations.json`, restart server, check endpoint and sketch output.

## 4. Documentation
- Document JSON schema and config usage in `README.md`.
- Add migration notes for moving to config-driven relations.

## 5. Migration & Validation
- Remove all hardcoded relation and copula logic from Java.
- Validate that all relation extraction and labeling is config-driven.
- Confirm that noun_adj_predicates and copula set are fully externalized.

## 6. Timeline & Milestones
- Day 1: Draft schema, create loader, basic integration.
- Day 2: Refactor relation logic, implement endpoint, write tests.
- Day 3: Documentation, migration, validation, review.

## 7. Risks & Mitigations
- **Risk:** Loader fails on bad config → Mitigation: strict validation, clear error messages.
- **Risk:** Missed hardcoded logic → Mitigation: code search for relation/collocate/copula keywords, thorough review.
- **Risk:** API/logic drift → Mitigation: test with real grammars and queries.

---

**Next: Begin with schema and loader implementation.**

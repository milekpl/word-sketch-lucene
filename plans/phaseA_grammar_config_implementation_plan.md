# Phase A: Grammar-Driven CQL/Relation Config Implementation Plan

## 1. Goals
- Externalize all relation definitions (including copula sets) to JSON in `grammars/`
- Implement a JSON grammar loader in the backend (Java)
- Expose the active grammar/config via `/api/grammar/active`
- Remove all hardcoded relation tables and copula sets from Java
- Ensure reload-on-restart (no code rebuild required for grammar changes)
- Make noun_adj_predicates and copula set fully grammar/config-driven

## 2. Steps

### 2.1. Define JSON Schema
- Design a JSON schema for relation families, CQL patterns, and copula sets
- Example structure:
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
      },
      ...
    ],
    "copulas": ["be", "seem", "become"]
  }
  ```
- Save as `grammars/relations.json`

### 2.2. Implement JSON Loader
- Add a loader in Java (e.g., `GrammarConfigLoader.java`)
- On server startup, load `grammars/relations.json`
- Validate against schema; fail fast on error
- Provide access to loaded relations and copula sets throughout backend

### 2.3. Refactor Relation Logic
- Remove all hardcoded relation tables and copula sets
- Refactor `HybridQueryExecutor`, `WordSketchApiServer`, and `ConcordanceExplorer` to use loaded config
- Ensure noun_adj_predicates and all other relations are defined by config

### 2.4. Expose Active Grammar API
- Add `/api/grammar/active` endpoint
- Return the loaded grammar config as JSON
- Document endpoint in README

### 2.5. Test and Validate
- Unit test loader and endpoint
- Manual test: edit `grammars/relations.json`, restart server, check `/api/grammar/active` and `/api/sketch`
- Validate that noun_adj_predicates and copula set are fully config-driven

### 2.6. Documentation
- Document JSON schema and usage in `README.md`
- Add migration notes for moving from hardcoded to config-driven relations

## 3. Deliverables
- `grammars/relations.json` (example and schema)
- `src/main/java/pl/marcinmilkowski/word_sketch/config/GrammarConfigLoader.java`
- Refactored backend using config
- `/api/grammar/active` endpoint
- Updated `README.md`

## 4. Next Steps (Phase B+)
- Add hot-reload support (optional)
- Expose grammar editing in UI (future)
- Move all relation/CQL logic to config
- Enable user-facing CQL concordance endpoint

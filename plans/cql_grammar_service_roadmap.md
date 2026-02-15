# CQL Grammar Service Roadmap (Co-Planning Draft)

Date: 2026-02-14
Status: Draft for agreement

## Why this roadmap
Your requirement is clear:
1. End users must be able to query concordances via CQL.
2. Noun adjective predicates must be true grammar-constrained relations (not loose proximity).
3. Grammar/rule definitions must be inspectable and configurable (JSON over hardcoded/M4-only).
4. Precompute should stop producing lax relations that do not match grammar constraints.

This roadmap aligns the server with a long-term goal: a fast, full corpus query service with transparent grammar configuration.

---

## Scope

### In scope
- User-facing CQL concordance endpoint with exact verification.
- JSON grammar configuration as runtime source of truth (loaded at server startup).
- Relation definitions and execution modes declared in JSON, not hardcoded in Java.
- Relation-aware precompute (versioned against grammar config).
- Inspectability endpoints for active grammar/rules.

### Out of scope (for first delivery)
- Full M4 macro compatibility parser.
- Hot-reload in production (can be phase 2).
- Multi-language runtime switching per request.

---

## Target architecture

### 1) Grammar config layer (JSON)
Create a JSON schema that defines:
- relation id, label, headword POS group, collocate POS group
- execution mode: `precomputed` | `scan_exact`
- CQL templates (with `%HEADWORD%` substitution)
- copular verb sets and optional lexical sets by language/profile
- constraints used by concordance validation

Example relation sketch (conceptual):
```json
{
  "id": "noun_adj_predicates",
  "label": "Adjectives (predicative)",
  "headPosGroup": "noun",
  "collocatePosGroup": "adj",
  "executionMode": "scan_exact",
  "cql": {
    "type": "template",
    "pattern": "[lemma=\"%HEADWORD%\"] [lemma in COPULAR] [tag=\"JJ.*\"]"
  }
}
```

### 2) Query execution modes
- `scan_exact`: candidate retrieval + token-level verifier for full CQL semantics.
- `precomputed`: only for relations proven equivalent to precomputed model.
- `hybrid`: precomputed candidates + exact verifier filter (optional intermediate mode).

### 3) Concordance service for end users
- Add endpoint: `POST /api/concordance/cql`
- Request includes CQL, optional headword, limit, context window.
- Response includes structured hits + highlighted spans + optional captured labels.

### 4) Relation-aware precompute
- Precompute keys become relation-specific, not just headword+collocate token type.
- Persist grammar fingerprint/version with artifacts.
- At query time, reject incompatible precompute artifacts if grammar changed.

---

## Phased delivery

## Phase A — Foundation (config + visibility)
1. Add grammar JSON schema and validation.
2. Add English profile JSON under `grammars/`.
3. Add grammar loader at server startup.
4. Add endpoint: `GET /api/grammar/active` (inspect loaded relations, profiles, fingerprints).
5. Replace hardcoded relation table in API server with loaded relations.

Acceptance:
- Server starts only with valid grammar config.
- UI/API can show loaded relation ids and execution modes.

## Phase B — End-user CQL concordance
1. Implement `POST /api/concordance/cql`.
2. Use existing parser/compiler + exact verifier path.
3. Return captures and offsets for highlighting.
4. Add safety guards (max window, max hits, timeout).

Acceptance:
- User submits CQL and gets deterministic concordance matches.
- Query behavior is inspectable (mode + verifier stats in response metadata).

## Phase C — Critical predicate correctness
1. Move `noun_adj_predicates` entirely to grammar JSON with explicit copular set.
2. Remove hardcoded copular verb list from executor.
3. Make concordance validation consume grammar-defined constraints.

Acceptance:
- Predicate relation output changes only via JSON grammar edits.
- No Java code edit required to tune copular list.

## Phase D — Relation-aware precompute
1. Define precompute relation contract (which relations are precomputable).
2. Extend precompute artifact format:
   - relationId
   - grammar fingerprint
   - execution assumptions
3. Update precompute command to generate relation-scoped data.
4. Update query path to use relation-specific precompute.

Acceptance:
- Precomputed results align with relation semantics.
- Mismatch diagnostics for stale/incompatible artifacts are explicit.

## Phase E — UI integration
1. Add CQL Concordance tab in webapp.
2. Show active grammar profile + relation mode badges.
3. Add debug panel: query plan (`precomputed` vs `scan_exact`), verifier stats.

Acceptance:
- End users can run CQL directly from webapp.
- Users can inspect why a relation is computed a certain way.

---

## Data contracts to add

### `POST /api/concordance/cql` request
- `cql` (required)
- `limit` (default 50)
- `contextWindow` (default 8)
- `headword` (optional)
- `profile` (optional, default active profile)

### `POST /api/concordance/cql` response
- `status`
- `query` metadata
- `matches[]` with sentence, token spans, captures
- `execution` metadata (`mode`, candidate hits, verified hits, timing)

### `GET /api/grammar/active`
- active profile id/version
- relation list + execution modes
- lexical sets (e.g., copular verbs)
- fingerprint/hash

---

## Risks and mitigations

1. **Performance regression in exact CQL**
- Mitigation: candidate-query selectivity, caps, caching, timeout metadata.

2. **Config drift vs precompute artifacts**
- Mitigation: grammar fingerprint embedded in precompute; strict compatibility check.

3. **Overly broad grammar rules causing noisy results**
- Mitigation: profile-specific lexical sets and unit/integration gold tests.

---

## Migration strategy

1. Keep current endpoints stable.
2. Introduce grammar config in parallel with hardcoded defaults.
3. Switch defaults to config-driven after parity checks.
4. Deprecate hardcoded relation definitions.

---

## Proposed immediate next sprint (1–2 weeks)

1. Grammar JSON schema + loader + `GET /api/grammar/active`.
2. Externalize `noun_adj_predicates` and copular verbs to JSON.
3. Add `POST /api/concordance/cql` MVP with exact verifier.
4. Add 10–20 gold tests for predicate patterns and core noun/verb relations.

---

## Decisions already agreed in this planning session
- Grammar source of truth: external files in `grammars/`
- Runtime behavior: reload on restart
- Copular set policy: grammar-defined list per language/profile

---

## Open decisions to finalize before implementation starts
1. JSON format choice: strict custom schema vs JSON5 (comments allowed).
2. Whether to include macro-like aliases in JSON v1 (minimal) or v2.
3. Limit defaults for `POST /api/concordance/cql`.
4. Whether webapp CQL tab is in same release as backend MVP.

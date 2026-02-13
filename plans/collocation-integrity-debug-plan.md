# Collocation Integrity Debug Plan

## Goal
Identify and fix the root cause(s) of massive `no_span_example` mismatches in `/api/diagnostics/collocation-integrity`.

---

## Key hypothesis (Priority 0)
**Pipeline mismatch between index type and query mode** is likely a major contributor.

- `conllu` command builds a **legacy token-per-document** index (`ConllUProcessor` + `LuceneIndexer`).
- PRECOMPUTED diagnostics and concordance use hybrid assumptions (sentence docs, span behavior, `tokens`/`lemma_ids` workflow).
- If a legacy index is served as hybrid or paired with a hybrid-oriented `collocations.bin`, `no_span_example` can approach 100%.

---

## Phase 1 — Repro and environment fingerprint (must do first)

### 1. Capture runtime fingerprint script
Create a script (`scripts/diagnostics/fingerprint.ps1`) that prints:
- index path
- Lucene field list from first segment
- doc count and sample stored fields
- presence of `sentence_id`, `doc_id`, `tokens`, `lemma_ids`, `text`
- stats source (`stats.bin` path, lemma count)
- collocations.bin header (`entryCount`, `window`, `topK`)

**Expected output artifact:** `diagnostics/fingerprint.json`

### 2. Capture integrity snapshot
Save current endpoint response as immutable baseline:
- `diagnostics/integrity_report.raw.json`
- `diagnostics/integrity_summary.tsv` (headword, collocate_count, mismatch_count, mismatch_ratio)

### 3. Add a simple assertion
If `mismatch_ratio >= 0.95` for many headwords, flag as systemic not token-level.

---

## Phase 2 — Validate index/build compatibility (fast discriminator)

### 4. Verify index type compatibility
For active server index:
- detect via field presence (`sentence_id` => hybrid, `doc_id` => legacy)
- assert server executor type matches index type

**If mismatch:**
- block PRECOMPUTED diagnostics with 400 + explicit message
- recommend rebuild with `single-pass` or `hybrid-index`

### 5. Verify collocations provenance
Add fingerprint to collocations metadata (future fix):
- index UUID / segment generation
- stats checksum
- lexicon checksum

For now, compare:
- file mtimes (`stats.bin`, `lexicon.bin`, `collocations.bin`)
- entry count plausibility vs vocabulary size

---

## Phase 3 — Pair-level witness debugging (headword drill-down)

### 6. Build witness script for problematic pairs
Script (`scripts/diagnostics/witness_pairs.ps1`) input:
- integrity report
- headword(s)

For each pair (headword, collocate):
1. `docFreq(lemma=headword)`
2. `docFreq(lemma=collocate)`
3. `SpanNear(headword, collocate, slop=window)` count
4. dump top example doc IDs + token sequence if count > 0

Output `diagnostics/witness_<timestamp>.tsv`.

### 7. Stratify failures
Classify mismatches:
- missing_headword
- missing_collocate
- both_present_but_no_span
- malformed_lemma_pattern (DOI/PMID/symbolic)

This tells whether issue is data, mapping, or algorithm.

---

## Phase 4 — Builder-path validation (unit + integration)

### 8. Add deterministic corpus fixture
Create tiny synthetic CoNLL-U fixture with known co-occurrence counts and edge tokens.

### 9. Add builder invariants tests
New tests:
1. **ID-mapping consistency** (`lemma_ids` ↔ `lexicon.bin`): decoded id maps to expected lemma.
2. **Top-K witness invariant**: each emitted collocate has at least one witness span in the indexed corpus.
3. **No-unknown-id collapse**: fallback mode must never map unknown lemma to ID `0` silently.
4. **Case normalization invariant**: same normalization for indexing, stats, and collocation build.

### 10. Differential test between algorithms
For same index and fixed seed headwords:
- compare PRECOMPUTED vs SAMPLE_SCAN overlap and witness rates.
- fail if PRECOMPUTED witness rate drops below threshold (e.g. 0.8 in fixture).

---

## Phase 5 — Suspected bug hotspots to inspect/fix

### Hotspot A: legacy/hybrid confusion
- `Main conllu` currently builds legacy index.
- README quick-start currently leads users to `conllu` path, then PRECOMPUTED server mode.
- Fix: make quick-start default to `single-pass` (or `hybrid-index` + collocation build), and guard API behavior by index type.

### Hotspot B: V2 fallback unknown lemma handling
In `CollocationsBuilderV2.scanAndSpill` fallback (`tokens` decode): unknown lemma IDs are currently mapped to `0`.
- This can poison co-occurrence statistics.
- Fix: use sentinel `-1` and skip these tokens in pair generation.

### Hotspot C: provenance missing in collocations.bin
No strict check that `collocations.bin` belongs to current index/lexicon.
- Fix: embed fingerprint metadata and hard-fail on mismatch in runtime load.

---

## Phase 6 — Hardening and operational safeguards

### 11. Runtime safety gates
- If index type is LEGACY, disable PRECOMPUTED diagnostics and concordance pair span checks with clear message.
- If witness ratio for requested headword < threshold, include warning in API response (`data integrity low`).

### 12. Build-time quality report
Add `collocations-quality.json` during build:
- sampled headwords
- sampled collocates
- witness rate
- top anomaly classes

Fail build when witness rate is catastrophically low.

---

## Suggested execution order (1–2 days)

1. Phase 1 + Phase 2 (fingerprint + compatibility checks)
2. Phase 3 (witness script + failure stratification)
3. Phase 4 tests on synthetic fixture
4. Apply fixes for Hotspot A/B/C
5. Re-run integrity report and compare baseline

---

## Success criteria

- Integrity report for representative sample no longer shows blanket 100/100 mismatch.
- PRECOMPUTED results have measurable witness rate and non-empty concordance for genuine pairs.
- Build path is explicit and safe: no accidental legacy index + hybrid PRECOMPUTED combination.
- CI includes regression tests preventing reintroduction.

# Claude Blind Reviewer Launch Prompt

You are an isolated blind reviewer. Do not use prior chat context, prior score history, or target-score anchoring.

Session id: ext_20260310_142751_eebadc31
Session token: 84d413591037fc14a4d8242d27de8356
Blind packet: /mnt/d/git/word-sketch-lucene/.desloppify/review_packet_blind.json
Template JSON: /mnt/d/git/word-sketch-lucene/.desloppify/external_review_sessions/ext_20260310_142751_eebadc31/review_result.template.json
Output JSON path: /mnt/d/git/word-sketch-lucene/.desloppify/external_review_sessions/ext_20260310_142751_eebadc31/review_result.json

--- Batch 11: design_coherence ---
Rationale: seed files for design_coherence review
DIMENSION TO EVALUATE:

## design_coherence
Are structural design decisions sound — functions focused, abstractions earned, patterns consistent?
Look for:
- Functions doing too many things — multiple distinct responsibilities in one body
- Parameter lists that should be config/context objects — many related params passed together
- Files accumulating issues across many dimensions — likely mixing unrelated concerns
- Deep nesting that could be flattened with early returns or extraction
- Repeated structural patterns that should be data-driven
Skip:
- Functions that are long but have a single coherent responsibility
- Parameter lists where grouping would obscure meaning
- Files that are large because their domain is genuinely complex, not because they mix concerns
- Nesting that is inherent to the problem (e.g., recursive tree processing)

Seed files (start here):
- src/main/java/pl/marcinmilkowski/word_sketch/api/WordSketchApiServer.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabQueryExecutor.java

Mechanical concern signals — navigation aid, not scoring evidence:
Confirm or refute each with your own code reading. Report only confirmed defects.
  - [design_concern] src/main/java/pl/marcinmilkowski/word_sketch/api/WordSketchApiServer.java
    summary: Design signals from structural
    question: Review the flagged patterns — are they design problems that need addressing, or acceptable given the file's role?
    evidence: Flagged by: structural
    evidence: File size: 1042 lines
  - [design_concern] src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabQueryExecutor.java
    summary: Design signals from structural
    question: Review the flagged patterns — are they design problems that need addressing, or acceptable given the file's role?
    evidence: Flagged by: structural
    evidence: File size: 545 lines

--- Batch 12: contract_coherence ---
Rationale: seed files for contract_coherence review
DIMENSION TO EVALUATE:

## contract_coherence
Functions and modules that honor their stated contracts
Look for:
- Return type annotation lies: declared type doesn't match all return paths
- Docstring/signature divergence: params described in docs but not in function signature
- Functions named getX that mutate state (side effect hidden behind getter name)
- Module-level API inconsistency: some exports follow a pattern, one doesn't
- Error contracts: function says it throws but silently returns None, or vice versa
Skip:
- Protocol/interface stubs (abstract methods with placeholder returns)
- Test helpers where loose typing is intentional
- Overloaded functions with multiple valid return types

Seed files (start here):
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabQueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/Main.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/WordSketchApiServer.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabSnippetParser.java
- src/main/java/pl/marcinmilkowski/word_sketch/indexer/blacklab/BlackLabConllUIndexer.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/HttpApiUtils.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/CQLVerifier.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/TokenWindow.java
- src/main/java/pl/marcinmilkowski/word_sketch/config/GrammarConfigLoader.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/SemanticFieldExplorer.java
- src/main/java/pl/marcinmilkowski/word_sketch/tagging/SimpleTagger.java
- src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLParser.java
- src/main/java/pl/marcinmilkowski/word_sketch/viz/RadialPlot.java
- src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLPattern.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/PatternSubstitution.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/QueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LongIntHashMap.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/AbstractPatternPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/AndPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/OrPredicate.java

--- Batch 13: logic_clarity ---
Rationale: seed files for logic_clarity review
DIMENSION TO EVALUATE:

## logic_clarity
Control flow and logic that provably does what it claims
Look for:
- Identical if/else or ternary branches (same code on both sides)
- Dead code paths: code after unconditional return/raise/throw/break
- Always-true or always-false conditions (e.g. checking a constant)
- Redundant null/undefined checks on values that cannot be null
- Async functions that never await (synchronous wrapped in async)
- Boolean expressions that simplify: `if x: return True else: return False`
Skip:
- Deliberate no-op branches with explanatory comments
- Framework lifecycle methods that must be async by contract
- Guard clauses that are defensive by design

Seed files (start here):
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabQueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/Main.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/WordSketchApiServer.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabSnippetParser.java
- src/main/java/pl/marcinmilkowski/word_sketch/indexer/blacklab/BlackLabConllUIndexer.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/HttpApiUtils.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/CQLVerifier.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/TokenWindow.java
- src/main/java/pl/marcinmilkowski/word_sketch/config/GrammarConfigLoader.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/SemanticFieldExplorer.java
- src/main/java/pl/marcinmilkowski/word_sketch/tagging/SimpleTagger.java
- src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLParser.java
- src/main/java/pl/marcinmilkowski/word_sketch/viz/RadialPlot.java
- src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLPattern.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/PatternSubstitution.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/QueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LongIntHashMap.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/AbstractPatternPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/AndPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/OrPredicate.java

--- Batch 14: type_safety ---
Rationale: seed files for type_safety review
DIMENSION TO EVALUATE:

## type_safety
Type annotations that match runtime behavior
Look for:
- Return type annotations that don't cover all code paths (e.g., -> str but can return None)
- Parameters typed as X but called with Y (e.g., str param receiving None)
- Union types that could be narrowed (Optional used where None is never valid)
- Missing annotations on public API functions
- Type: ignore comments without explanation
- TypedDict fields marked Required but accessed via .get() with defaults — the type promises a shape the code doesn't trust
- Parameters typed as dict[str, Any] where a specific TypedDict or dataclass exists
- Enum types defined in the codebase but bypassed with raw string or int literal comparisons — see enum_bypass_patterns evidence
- Parallel type definitions: a Literal alias that duplicates an existing enum's values
Skip:
- Untyped private helpers in well-typed modules
- Dynamic framework code where typing is impractical
- Test code with loose typing

Seed files (start here):
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabQueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/Main.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/WordSketchApiServer.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabSnippetParser.java
- src/main/java/pl/marcinmilkowski/word_sketch/indexer/blacklab/BlackLabConllUIndexer.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/HttpApiUtils.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/CQLVerifier.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/TokenWindow.java
- src/main/java/pl/marcinmilkowski/word_sketch/config/GrammarConfigLoader.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/SemanticFieldExplorer.java
- src/main/java/pl/marcinmilkowski/word_sketch/tagging/SimpleTagger.java
- src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLParser.java
- src/main/java/pl/marcinmilkowski/word_sketch/viz/RadialPlot.java
- src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLPattern.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/PatternSubstitution.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/QueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LongIntHashMap.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/AbstractPatternPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/AndPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/OrPredicate.java

--- Batch 15: naming_quality ---
Rationale: no direct batch mapping for naming_quality; using representative files
DIMENSION TO EVALUATE:

## naming_quality
Function/variable/file names that communicate intent
Look for:
- Generic verbs that reveal nothing: process, handle, do, run, manage
- Name/behavior mismatch: getX() that mutates state, isX() returning non-boolean
- Vocabulary divergence from codebase norms (context provides the norms)
- Abbreviations inconsistent with codebase conventions
Skip:
- Standard framework names (render, mount, useEffect)
- Short-lived loop variables (i, j, k)
- Well-known abbreviations matching codebase convention (ctx, req, res)

Seed files (start here):
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LongIntHashMap.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/AbstractPatternPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/AndPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabQueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/HttpApiUtils.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/PatternSubstitution.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/WordSketchApiServer.java
- src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLParseException.java
- src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLParser.java
- src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLPattern.java
- src/main/java/pl/marcinmilkowski/word_sketch/Main.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabSnippetParser.java
- src/main/java/pl/marcinmilkowski/word_sketch/indexer/blacklab/BlackLabConllUIndexer.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/CQLVerifier.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/TokenWindow.java
- src/main/java/pl/marcinmilkowski/word_sketch/config/GrammarConfigLoader.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/SemanticFieldExplorer.java
- src/main/java/pl/marcinmilkowski/word_sketch/tagging/SimpleTagger.java
- src/main/java/pl/marcinmilkowski/word_sketch/viz/RadialPlot.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/QueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/OrPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/TokenPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LogDiceCalculator.java


YOUR TASK: Read the code for this batch's dimension. Judge how well the codebase serves a developer from that perspective. The dimension rubric above defines what good looks like. Cite specific observations that explain your judgment.

Mechanical scan evidence — navigation aid, not scoring evidence:
The blind packet contains `holistic_context.scan_evidence` with aggregated signals from all mechanical detectors — including complexity hotspots, error hotspots, signal density index, boundary violations, and systemic patterns. Use these as starting points for where to look beyond the seed files.

Task requirements:
1. Read the blind packet's `system_prompt` — it contains scoring rules and calibration.
2. Start from the seed files, then freely explore the repository to build your understanding.
3. Keep issues and scoring scoped to this batch's dimension.
4. Respect scope controls: do not include files/directories marked by `exclude`, `suppress`, or non-production zone overrides.
5. Return 0-200 issues for this batch (empty array allowed).
6. For package_organization, ground scoring in objective structure signals from `holistic_context.structure` (root_files fan_in/fan_out roles, directory_profiles, coupling_matrix). Prefer thresholded evidence (for example: fan_in < 5 for root stragglers, import-affinity > 60%, directories > 10 files with mixed concerns).
7. Suggestions must include a staged reorg plan (target folders, move order, and import-update/validation commands).
8. Also consult `holistic_context.structure.flat_dir_issues` for directories flagged as overloaded, fragmented, or thin-wrapper patterns.
9. For abstraction_fitness, use evidence from `holistic_context.abstractions`:
10. - `delegation_heavy_classes`: classes where most methods forward to an inner object — entries include class_name, delegate_target, sample_methods, and line number.
11. - `facade_modules`: re-export-only modules with high re_export_ratio — entries include samples (re-exported names) and loc.
12. - `typed_dict_violations`: TypedDict fields accessed via .get()/.setdefault()/.pop() — entries include typed_dict_name, violation_type, field, and line number.
13. - `complexity_hotspots`: files where mechanical analysis found extreme parameter counts, deep nesting, or disconnected responsibility clusters.
14. Include `delegation_density`, `definition_directness`, and `type_discipline` alongside existing sub-axes in dimension_notes when evidence supports it.
15. For initialization_coupling, use evidence from `holistic_context.scan_evidence.mutable_globals` and `holistic_context.errors.mutable_globals`. Investigate initialization ordering dependencies, coupling through shared mutable state, and whether state should be encapsulated behind a proper registry/context manager.
16. For design_coherence, use evidence from `holistic_context.scan_evidence.signal_density` — files where multiple mechanical detectors fired. Investigate what design change would address multiple signals simultaneously. Check `scan_evidence.complexity_hotspots` for files with high responsibility cluster counts.
17. For error_consistency, use evidence from `holistic_context.errors.exception_hotspots` — files with concentrated exception handling issues. Investigate whether error handling is designed or accidental. Check for broad catches masking specific failure modes.
18. For cross_module_architecture, also consult `holistic_context.coupling.boundary_violations` for import paths that cross architectural boundaries, and `holistic_context.dependencies.deferred_import_density` for files with many function-level imports (proxy for cycle pressure).
19. For convention_outlier, also consult `holistic_context.conventions.duplicate_clusters` for cross-file function duplication and `conventions.naming_drift` for directory-level naming inconsistency.
20. Workflow integrity checks: when reviewing orchestration/queue/review flows,
21. xplicitly look for loop-prone patterns and blind spots:
22. - repeated stale/reopen churn without clear exit criteria or gating,
23. - packet/batch data being generated but dropped before prompt execution,
24. - ranking/triage logic that can starve target-improving work,
25. - reruns happening before existing open review work is drained.
26. If found, propose concrete guardrails and where to implement them.
27. Complete `dimension_judgment` for your dimension — all three fields (strengths, issue_character, score_rationale) are required. Write the judgment BEFORE setting the score.
28. Do not edit repository files.
29. Return ONLY valid JSON, no markdown fences.

Scope enums:
- impact_scope: "local" | "module" | "subsystem" | "codebase"
- fix_scope: "single_edit" | "multi_file_refactor" | "architectural_change"

Output schema:
{
  "session": {"id": "<preserve from template>", "token": "<preserve from template>"},
  "assessments": {"<dimension>": <0-100 with one decimal place>},
  "dimension_notes": {
    "<dimension>": {
      "evidence": ["specific code observations"],
      "impact_scope": "local|module|subsystem|codebase",
      "fix_scope": "single_edit|multi_file_refactor|architectural_change",
      "confidence": "high|medium|low"
    }
  },
  "issues": [{
    "dimension": "<dimension>",
    "identifier": "short_id",
    "summary": "one-line defect summary",
    "related_files": ["relative/path.py"],
    "evidence": ["specific code observation"],
    "suggestion": "concrete fix recommendation",
    "confidence": "high|medium|low",
    "impact_scope": "local|module|subsystem|codebase",
    "fix_scope": "single_edit|multi_file_refactor|architectural_change",
    "root_cause_cluster": "optional_cluster_name"
  }]
}

Session requirements:
1. Keep `session.id` exactly `ext_20260310_142751_eebadc31`.
2. Keep `session.token` exactly `84d413591037fc14a4d8242d27de8356`.
3. Do not include provenance metadata (CLI injects canonical provenance).


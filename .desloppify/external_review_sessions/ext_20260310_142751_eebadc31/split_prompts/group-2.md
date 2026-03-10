# Claude Blind Reviewer Launch Prompt

You are an isolated blind reviewer. Do not use prior chat context, prior score history, or target-score anchoring.

Session id: ext_20260310_142751_eebadc31
Session token: 84d413591037fc14a4d8242d27de8356
Blind packet: /mnt/d/git/word-sketch-lucene/.desloppify/review_packet_blind.json
Template JSON: /mnt/d/git/word-sketch-lucene/.desloppify/external_review_sessions/ext_20260310_142751_eebadc31/review_result.template.json
Output JSON path: /mnt/d/git/word-sketch-lucene/.desloppify/external_review_sessions/ext_20260310_142751_eebadc31/review_result.json

--- Batch 6: mid_level_elegance ---
Rationale: seed files for mid_level_elegance review
DIMENSION TO EVALUATE:

## mid_level_elegance
Quality of handoffs and integration seams across modules and layers
Look for:
- Inputs/outputs across boundaries are explicit, minimal, and unsurprising
- Data translation at boundaries happens in one obvious place
- Error and lifecycle propagation across boundaries follows predictable patterns
- Orchestration reads as composition of collaborators, not tangled back-and-forth calls
- Integration seams avoid glue-code entropy (ad-hoc mappers and boundary conditionals)
Skip:
- When top-level decomposition/package shape is the PRIMARY issue, report under high_level_elegance
- When implementation craft inside one function/class is the PRIMARY issue, report under low_level_elegance
- Pure API/type contract defects with no seam design impact (belongs to contract_coherence)
- Standalone naming/style preferences that do not affect handoffs

Seed files (start here):
- src/main/java/pl/marcinmilkowski/word_sketch/query/AbstractPatternPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/AndPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabQueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/HttpApiUtils.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/PatternSubstitution.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/WordSketchApiServer.java
- src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLParseException.java
- src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLParser.java
- src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLPattern.java

--- Batch 7: ai_generated_debt ---
Rationale: seed files for ai_generated_debt review
DIMENSION TO EVALUATE:

## ai_generated_debt
LLM-hallmark patterns: restating comments, defensive overengineering, boilerplate
Look for:
- Restating comments that echo the code without adding insight (// increment counter above i++)
- Nosy debug logging: entry/exit logs on every function, full object dumps to console
- Defensive overengineering: null checks on non-nullable typed values, try-catch around pure expressions
- Docstring bloat: multi-line docstrings on trivial 2-line functions
- Pass-through wrapper functions with no added logic (just forward args to another function)
- Generic names in domain code: handleData, processItem, doOperation where domain terms exist
- Identical boilerplate error handling copied verbatim across multiple files
Skip:
- Comments explaining WHY (business rules, non-obvious constraints, external dependencies)
- Defensive checks at genuine API boundaries (user input, network, file I/O)
- Generated code (protobuf, GraphQL codegen, ORM migrations)
- Wrapper functions that add auth, logging, metrics, or caching

Seed files (start here):
- src/main/java/pl/marcinmilkowski/word_sketch/Main.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/PatternSubstitution.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/WordSketchApiServer.java
- src/main/java/pl/marcinmilkowski/word_sketch/config/GrammarConfigLoader.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabQueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabSnippetParser.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/QueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/TokenPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LogDiceCalculator.java

--- Batch 8: incomplete_migration ---
Rationale: seed files for incomplete_migration review
DIMENSION TO EVALUATE:

## incomplete_migration
Old+new API coexistence, deprecated-but-called symbols, stale migration shims
Look for:
- Old and new API patterns coexisting: class+functional components, axios+fetch, moment+dayjs
- Deprecated symbols still called by active code (@deprecated, DEPRECATED markers)
- Compatibility shims that no caller actually needs anymore
- Mixed JS/TS files for the same module (incomplete TypeScript migration)
- Stale migration TODOs: TODO/FIXME referencing 'migrate', 'legacy', 'old api', 'remove after'
Skip:
- Active, intentional migrations with tracked progress
- Backward-compatibility for external consumers (published APIs, libraries)
- Gradual rollouts behind feature flags with clear ownership

Seed files (start here):
- src/main/java/pl/marcinmilkowski/word_sketch/Main.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/PatternSubstitution.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/WordSketchApiServer.java
- src/main/java/pl/marcinmilkowski/word_sketch/config/GrammarConfigLoader.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabQueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabSnippetParser.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/QueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/TokenPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LogDiceCalculator.java

--- Batch 9: package_organization ---
Rationale: seed files for package_organization review
DIMENSION TO EVALUATE:

## package_organization
Directory layout quality and navigability: whether placement matches ownership and change boundaries
Look for:
- Use holistic_context.structure as objective evidence: root_files (fan_in/fan_out + role), directory_profiles (file_count/avg fan-in/out), and coupling_matrix (cross-directory edges)
- Straggler roots: root-level files with low fan-in (<5 importers) that share concern/theme with other files should move under a focused package
- Import-affinity mismatch: file imports/references are mostly from one sibling domain (>60%), but file lives outside that domain
- Coupling-direction failures: reciprocal/bidirectional directory edges or obvious downstream→upstream imports indicate boundary placement problems
- Flat directory overload: >10 files with mixed concerns and low cohesion should be split into purpose-driven subfolders
- Ambiguous folder naming: directory names do not reflect contained responsibilities
Skip:
- Root-level files that ARE genuinely core — high fan-in (≥5 importers), imported across multiple subdirectories (cli.py, state.py, utils.py, config.py)
- Small projects (<20 files) where flat structure is appropriate
- Framework-imposed directory layouts (src/, lib/, dist/, __pycache__/)
- Test directories mirroring production structure
- Aesthetic preferences without measurable navigation, ownership, or coupling impact

Seed files (start here):
- src/main/java/pl/marcinmilkowski/word_sketch/query/AbstractPatternPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/AndPredicate.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabQueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/HttpApiUtils.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/PatternSubstitution.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/WordSketchApiServer.java
- src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLParseException.java
- src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLParser.java
- src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLPattern.java

--- Batch 10: initialization_coupling ---
Rationale: seed files for initialization_coupling review
DIMENSION TO EVALUATE:

## initialization_coupling
Boot-order dependencies, import-time side effects, global singletons
Look for:
- Module-level code that depends on another module having been imported first
- Import-time side effects: DB connections, file I/O, network calls at module scope
- Global singletons where creation order matters across modules
- Environment variable reads at import time (fragile in testing)
- Circular init dependencies hidden behind conditional or lazy imports
- Module-level constants computed at import time alongside a dynamic getter function — consumers referencing the stale snapshot instead of calling the getter
Skip:
- Standard library initialization (logging.basicConfig)
- Framework bootstrap (app.configure, server.listen)

Seed files (start here):
- src/main/java/pl/marcinmilkowski/word_sketch/api/WordSketchApiServer.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabQueryExecutor.java


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


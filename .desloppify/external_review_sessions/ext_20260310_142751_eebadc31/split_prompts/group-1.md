# Claude Blind Reviewer Launch Prompt

You are an isolated blind reviewer. Do not use prior chat context, prior score history, or target-score anchoring.

Session id: ext_20260310_142751_eebadc31
Session token: 84d413591037fc14a4d8242d27de8356
Blind packet: /mnt/d/git/word-sketch-lucene/.desloppify/review_packet_blind.json
Template JSON: /mnt/d/git/word-sketch-lucene/.desloppify/external_review_sessions/ext_20260310_142751_eebadc31/review_result.template.json
Output JSON path: /mnt/d/git/word-sketch-lucene/.desloppify/external_review_sessions/ext_20260310_142751_eebadc31/review_result.json

--- Batch 1: cross_module_architecture ---
Rationale: seed files for cross_module_architecture review
DIMENSION TO EVALUATE:

## cross_module_architecture
Dependency direction, cycles, hub modules, and boundary integrity
Look for:
- Layer/dependency direction violations repeated across multiple modules
- Cycles or hub modules that create large blast radius for common changes
- Documented architecture contracts drifting from runtime (e.g. dynamic import boundaries)
- Cross-module coordination through shared mutable state or import-time side effects
- Compatibility shim paths that persist without active external need and blur boundaries
- Cross-package duplication that indicates a missing shared boundary
- Subsystem or package consuming a disproportionate share of the codebase — see package_size_census evidence
Skip:
- Intentional facades/re-exports with clear API purpose
- Framework-required patterns (Django settings, plugin registries)
- Package naming/placement tidy-ups without boundary harm (belongs to package_organization)
- Local readability/craft issues (belongs to low_level_elegance)

Seed files (start here):
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LongIntHashMap.java

--- Batch 2: high_level_elegance ---
Rationale: seed files for high_level_elegance review
DIMENSION TO EVALUATE:

## high_level_elegance
Clear decomposition, coherent ownership, domain-aligned structure
Look for:
- Top-level packages/files map to domain capabilities rather than historical accidents
- Ownership and change boundaries are predictable — a new engineer can explain why this exists
- Public surface (exports/entry points) is small and consistent with stated responsibility
- Project contracts and reference docs match runtime reality (README/structure/philosophy are trustworthy)
- Subsystem decomposition localizes change without surprising ripple edits
- A small set of architectural patterns is used consistently across major areas
Skip:
- When dependency direction/cycle/hub failures are the PRIMARY issue, report under cross_module_architecture (still include here if they materially blur ownership/decomposition)
- When handoff mechanics are the PRIMARY issue, report under mid_level_elegance (still include here if they materially affect top-level role clarity)
- When function/class internals are the PRIMARY issue, report under low_level_elegance or logic_clarity
- Pure naming/style nits with no impact on role clarity

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

--- Batch 3: abstraction_fitness ---
Rationale: seed files for abstraction_fitness review
DIMENSION TO EVALUATE:

## abstraction_fitness
Abstractions that pay for themselves with real leverage
Look for:
- Pass-through wrappers or interfaces that add no behavior, policy, or translation
- Cross-cutting wrapper chains where call depth increases without added value
- Interface/protocol families where most declared contracts have only one implementation
- Systemic util/helper dumping grounds that create low cohesion across modules
- Leaky abstractions: callers consistently bypass intended interfaces
- Wide options/context bag APIs that hide true domain boundaries
- Generic/type-parameter machinery used in only one concrete way
- Delegation-heavy classes where most methods forward to an inner object (high delegation ratio)
- Facade/re-export modules that define no logic of their own
- Getter functions whose body is solely return x.get(key) — the underlying type should be an object with properties instead of dict access
Skip:
- Dependency-injection or framework abstractions required for wiring/testability
- Adapters that intentionally isolate external API volatility
- Cases where abstraction clearly reduces duplication across multiple callers
- Thin wrappers that consistently enforce policy (auth/logging/metrics/caching)
- If the core issue is dependency direction or cycles, use cross_module_architecture

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

--- Batch 4: dependency_health ---
Rationale: seed files for dependency_health review
DIMENSION TO EVALUATE:

## dependency_health
Unused deps, version conflicts, multiple libs for same purpose, heavy deps
Look for:
- Multiple libraries for the same purpose (e.g. moment + dayjs, axios + fetch wrapper)
- Heavy dependencies pulled in for light use (e.g. lodash for one function)
- Circular dependency cycles visible in the import graph
- Unused dependencies in package.json/requirements.txt
- Version conflicts or pinning issues visible in lock files
Skip:
- Dev dependencies (test, build, lint tools)
- Peer dependencies required by frameworks

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

--- Batch 5: low_level_elegance ---
Rationale: seed files for low_level_elegance review
DIMENSION TO EVALUATE:

## low_level_elegance
Direct, precise function and class internals
Look for:
- Control flow is direct and intention-revealing; branches are necessary and distinct
- State mutation and side effects are explicit, local, and bounded
- Edge-case handling is precise without defensive sprawl
- Extraction level is balanced: avoids both monoliths and micro-fragmentation
- Helper extraction style is consistent across related modules
Skip:
- When file responsibility/package role is the PRIMARY issue, report under high_level_elegance
- When inter-module seam choreography is the PRIMARY issue, report under mid_level_elegance
- When dependency topology is the PRIMARY issue, report under cross_module_architecture
- Provable logic/type/error defects already captured by logic_clarity, type_safety, or error_consistency

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


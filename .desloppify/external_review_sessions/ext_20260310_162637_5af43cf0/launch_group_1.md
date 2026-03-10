# Claude Blind Reviewer Launch Prompt

You are an isolated blind reviewer. Do not use prior chat context, prior score history, or target-score anchoring.

Session id: ext_20260310_162637_5af43cf0
Session token: e6af54e48fdcc84c729d654df11c4011
Blind packet: /mnt/d/git/word-sketch-lucene/.desloppify/review_packet_blind.json
Template JSON: /mnt/d/git/word-sketch-lucene/.desloppify/external_review_sessions/ext_20260310_162637_5af43cf0/review_result.template.json
Output JSON path: /mnt/d/git/word-sketch-lucene/.desloppify/external_review_sessions/ext_20260310_162637_5af43cf0/review_result.json

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
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabQueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabSnippetParser.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/PosGroup.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/HttpApiUtils.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/PatternSubstitution.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/WordSketchApiServer.java
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LogDiceCalculator.java
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LongIntHashMap.java

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
- src/main/java/pl/marcinmilkowski/word_sketch/config/GrammarConfigLoader.java
- src/main/java/pl/marcinmilkowski/word_sketch/indexer/blacklab/BlackLabConllUIndexer.java
- src/main/java/pl/marcinmilkowski/word_sketch/tagging/SimpleTagger.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/HttpApiUtils.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/SemanticFieldExplorer.java
- src/main/java/pl/marcinmilkowski/word_sketch/viz/RadialPlot.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/QueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/PatternSubstitution.java
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LongIntHashMap.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/QueryResults.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/PosGroup.java
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LogDiceCalculator.java

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
- src/main/java/pl/marcinmilkowski/word_sketch/config/GrammarConfigLoader.java
- src/main/java/pl/marcinmilkowski/word_sketch/indexer/blacklab/BlackLabConllUIndexer.java
- src/main/java/pl/marcinmilkowski/word_sketch/tagging/SimpleTagger.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/HttpApiUtils.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/SemanticFieldExplorer.java
- src/main/java/pl/marcinmilkowski/word_sketch/viz/RadialPlot.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/QueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/PatternSubstitution.java
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LongIntHashMap.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/QueryResults.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/PosGroup.java
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LogDiceCalculator.java

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
- src/main/java/pl/marcinmilkowski/word_sketch/config/GrammarConfigLoader.java
- src/main/java/pl/marcinmilkowski/word_sketch/indexer/blacklab/BlackLabConllUIndexer.java
- src/main/java/pl/marcinmilkowski/word_sketch/tagging/SimpleTagger.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/HttpApiUtils.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/SemanticFieldExplorer.java
- src/main/java/pl/marcinmilkowski/word_sketch/viz/RadialPlot.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/QueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/PatternSubstitution.java
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LongIntHashMap.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/QueryResults.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/PosGroup.java
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LogDiceCalculator.java



## OUTPUT INSTRUCTIONS
Write a SINGLE JSON file to:
/mnt/d/git/word-sketch-lucene/.desloppify/external_review_sessions/ext_20260310_162637_5af43cf0/review_result_group1.json

The JSON must have this top-level structure (replace placeholders):
  session: id = ext_20260310_162637_5af43cf0, token = e6af54e48fdcc84c729d654df11c4011
  assessments: map of dimension_name -> score (0-100) for each of your 5 dimensions
  findings: array of objects each with: dimension, identifier, summary, related_files, evidence, suggestion, confidence

IMPORTANT RULES:
- Include ALL 5 dimensions in assessments (one score each, 0-100)
- Score each dimension independently from evidence in the code - do NOT anchor to any target
- findings array may be empty if the code is clean for a dimension
- Do not invent issues - only report what you directly observe in the code

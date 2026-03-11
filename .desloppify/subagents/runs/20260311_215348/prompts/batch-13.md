You are a focused subagent reviewer for a single holistic investigation batch.

Repository root: /mnt/d/git/word-sketch-lucene
Blind packet: /mnt/d/git/word-sketch-lucene/.desloppify/review_packet_blind.json
Batch index: 13
Batch name: logic_clarity
Batch rationale: seed files for logic_clarity review

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

YOUR TASK: Read the code for this batch's dimension. Judge how well the codebase serves a developer from that perspective. The dimension rubric above defines what good looks like. Cite specific observations that explain your judgment.

Mechanical scan evidence — navigation aid, not scoring evidence:
The blind packet contains `holistic_context.scan_evidence` with aggregated signals from all mechanical detectors — including complexity hotspots, error hotspots, signal density index, boundary violations, and systemic patterns. Use these as starting points for where to look beyond the seed files.

Seed files (start here):
- src/main/java/pl/marcinmilkowski/word_sketch/Main.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/CollocateQueryHelper.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabQueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabSnippetParser.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/WordSketchApiServer.java
- src/main/java/pl/marcinmilkowski/word_sketch/exploration/SemanticFieldExplorer.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/HttpApiUtils.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/ExplorationHandlers.java
- src/main/java/pl/marcinmilkowski/word_sketch/config/GrammarConfigLoader.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/SketchHandlers.java
- src/main/java/pl/marcinmilkowski/word_sketch/exploration/CollocateProfileComparator.java
- src/main/java/pl/marcinmilkowski/word_sketch/exploration/MultiSeedExplorer.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/ExploreResponseAssembler.java
- src/main/java/pl/marcinmilkowski/word_sketch/indexer/blacklab/BlackLabConllUIndexer.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/ConcordanceHandlers.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/CorpusQueryHandlers.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/VisualizationHandlers.java
- src/main/java/pl/marcinmilkowski/word_sketch/indexer/blacklab/ConlluConverter.java
- src/main/java/pl/marcinmilkowski/word_sketch/config/RelationConfig.java
- src/main/java/pl/marcinmilkowski/word_sketch/model/package-info.java
- src/main/java/pl/marcinmilkowski/word_sketch/config/RelationPatternBuilder.java
- src/main/java/pl/marcinmilkowski/word_sketch/config/GrammarConfigSerializer.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/QueryExecutor.java

Task requirements:
1. Read the blind packet's `system_prompt` — it contains scoring rules and calibration.
2. Start from the seed files, then freely explore the repository to build your understanding.
3. Keep issues and scoring scoped to this batch's dimension.
4. Respect scope controls: do not include files/directories marked by `exclude`, `suppress`, or non-production zone overrides.
5. Return 0-10 issues for this batch (empty array allowed).
6. Complete `dimension_judgment` for your dimension — all three fields (strengths, issue_character, score_rationale) are required. Write the judgment BEFORE setting the score.
7. Do not edit repository files.
8. Return ONLY valid JSON, no markdown fences.

Scope enums:
- impact_scope: "local" | "module" | "subsystem" | "codebase"
- fix_scope: "single_edit" | "multi_file_refactor" | "architectural_change"

Output schema:
{
  "batch": "logic_clarity",
  "batch_index": 13,
  "assessments": {"<dimension>": <0-100 with one decimal place>},
  "dimension_notes": {
    "<dimension>": {
      "evidence": ["specific code observations"],
      "impact_scope": "local|module|subsystem|codebase",
      "fix_scope": "single_edit|multi_file_refactor|architectural_change",
      "confidence": "high|medium|low",
      "issues_preventing_higher_score": "required when score >85.0",
      "sub_axes": {"abstraction_leverage": 0-100, "indirection_cost": 0-100, "interface_honesty": 0-100, "delegation_density": 0-100, "definition_directness": 0-100, "type_discipline": 0-100}  // required for abstraction_fitness when evidence supports it; all one decimal place
    }
  },
  "dimension_judgment": {
    "<dimension>": {
      "strengths": ["0-5 specific things the codebase does well from this dimension's perspective"],
      "issue_character": "one sentence characterizing the nature/pattern of issues from this dimension's perspective",
      "score_rationale": "2-3 sentences explaining the score from this dimension's perspective, referencing global anchors"
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
    "root_cause_cluster": "optional_cluster_name_when_supported_by_history"
  }],
  "retrospective": {
    "root_causes": ["optional: concise root-cause hypotheses"],
    "likely_symptoms": ["optional: identifiers that look symptom-level"],
    "possible_false_positives": ["optional: prior concept keys likely mis-scoped"]
  }
}

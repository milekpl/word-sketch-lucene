# Claude Blind Reviewer Launch Prompt

You are an isolated blind reviewer. Do not use prior chat context, prior score history, or target-score anchoring.

Session id: ext_20260310_162637_5af43cf0
Session token: e6af54e48fdcc84c729d654df11c4011
Blind packet: /mnt/d/git/word-sketch-lucene/.desloppify/review_packet_blind.json
Template JSON: /mnt/d/git/word-sketch-lucene/.desloppify/external_review_sessions/ext_20260310_162637_5af43cf0/review_result.template.json
Output JSON path: /mnt/d/git/word-sketch-lucene/.desloppify/external_review_sessions/ext_20260310_162637_5af43cf0/review_result.json

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
    evidence: File size: 1030 lines
  - [design_concern] src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabQueryExecutor.java
    summary: Design signals from structural
    question: Review the flagged patterns — are they design problems that need addressing, or acceptable given the file's role?
    evidence: Flagged by: structural
    evidence: File size: 547 lines

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
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabQueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/BlackLabSnippetParser.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/PosGroup.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/HttpApiUtils.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/PatternSubstitution.java
- src/main/java/pl/marcinmilkowski/word_sketch/api/WordSketchApiServer.java
- src/main/java/pl/marcinmilkowski/word_sketch/utils/LogDiceCalculator.java
- src/main/java/pl/marcinmilkowski/word_sketch/Main.java
- src/main/java/pl/marcinmilkowski/word_sketch/config/GrammarConfigLoader.java
- src/main/java/pl/marcinmilkowski/word_sketch/indexer/blacklab/BlackLabConllUIndexer.java
- src/main/java/pl/marcinmilkowski/word_sketch/tagging/SimpleTagger.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/SemanticFieldExplorer.java
- src/main/java/pl/marcinmilkowski/word_sketch/viz/RadialPlot.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/QueryExecutor.java
- src/main/java/pl/marcinmilkowski/word_sketch/query/QueryResults.java



## OUTPUT INSTRUCTIONS
Write a SINGLE JSON file to:
/mnt/d/git/word-sketch-lucene/.desloppify/external_review_sessions/ext_20260310_162637_5af43cf0/review_result_group3.json

The JSON must have this top-level structure (replace placeholders):
  session: id = ext_20260310_162637_5af43cf0, token = e6af54e48fdcc84c729d654df11c4011
  assessments: map of dimension_name -> score (0-100) for each of your 5 dimensions
  findings: array of objects each with: dimension, identifier, summary, related_files, evidence, suggestion, confidence

IMPORTANT RULES:
- Include ALL 5 dimensions in assessments (one score each, 0-100)
- Score each dimension independently from evidence in the code - do NOT anchor to any target
- findings array may be empty if the code is clean for a dimension
- Do not invent issues - only report what you directly observe in the code

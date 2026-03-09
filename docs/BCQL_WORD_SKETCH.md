# Writing Word‑Sketch Patterns in BCQL

This document shows how to author grammar patterns for the word‑sketch engine using
BlackLab Corpus Query Language (BCQL). It complements the official guide
(https://blacklab.ivdnt.org/guide/query-language/) by focusing on the features you
actually need for token‑level and relation‑based search in this project.

## Concepts

* **Token query** – a single lexical item constrained by attributes such as
  `xpos` (part‑of‑speech), `lemma`, `word`, or `deprel`.  (Older drafts used
  `tag` internally; `xpos` is what appears in the grammar file.)  In BCQL this is
  written inside square brackets, e.g. `[xpos="NN.*"]` or
  `[lemma="be|seem|appear"]`.
* **Sequence** – tokens separated by whitespace form a `SpanNear` sequence. The
  order matters unless you use a slop or `[]{min,max}` construct.
* **Pattern** – one or more tokens and operators describing the context in which
  we look for a head word and its collocate.
* **Head / collocate** – every relation configuration defines a `head_position`
  and a `collocate_position`. These are 1‑based indexes into the pattern tokens.
  During configuration they are used to label the two important tokens.

    To avoid ambiguity the loader rewrites your pattern so it contains exactly
    two numeric labels.  Label `1:` marks the head token, label `2:` marks the
    collocate token; all other tokens are unlabelled.  The loader uses the numeric labels inside each pattern to determine which token is the head (`1:`) and which is the collocate (`2:`).

    Example:

    ```json
    "pattern": "[xpos=\"NN.*\"] [lemma=\"be|appear\"] [xpos=\"JJ.*\"]"
    ```

* **Optional slop** – you may allow intervening tokens with a bounded gap.
  Use `[token]{min,max}` or rely on the per‑relation `default_slop` that the
  server applies when executing queries.

* **Dependency placeholders** – relations that operate on dependency fields can
  use the `{deprel=…}` syntax or `{head}` placeholder.  In these cases the
  token count validation is skipped because BlackLab resolves the placeholders
  internally.

## Grammar Configuration File

All relations are declared in `grammars/relations.json`. Each entry has the
following structure (fields marked _optional_ have defaults):

```json
{
  "id": "noun_adj_predicates",
  "name": "Adjectives (predicative)",          // human‑readable
  "description": "…",
  "pattern": "1:[tag=\"NN.*\"]  [lemma=\"be|seem\"] 2:[tag=\"JJ.*\"]",
  "head_position": 1,
  "collocate_position": 3,
  "default_slop": 8,           // optional; fallback if query omits slop
  "relation_type": "SURFACE", // SURFACE or DEP
  "dual": false                // treat head/collocate symmetrically
}
```

The loader validates that `head_position` and `collocate_position` fall within
the number of tokens present in the pattern.  Patterns must not be empty.
Duplicate `id` values are rejected.

### Adding new relations

1. Pick an unused `id` (see `plans/query-architecture-review.md` for schema).
2. Write a BCQL pattern with the two crucial elements marked by positions.
3. Optionally specify `default_slop` if the tokens may be separated in real
   data.
4. Restart the server (changes are loaded at startup).

## Token and Relation Querying Examples

### Single token search

Search for any noun tagged `NN.*`:
```bash
http://localhost:8080/api/query?pattern=[tag="NN.*"]
```

### Relation search via API

Each relation defined in `relations.json` becomes available under
`/api/sketch/{relationId}`.  For example:
```bash
curl "http://localhost:8080/api/sketch/noun_adj_predicates?lemma=hypothesis"
```
This will internally compile the stored BCQL pattern, substitute the lemma as
`head`, and execute the query with logDice scoring.

### Advanced pattern features

* **Alternation**: use `|` inside regexes (`[tag="VB.*|JJ.*"]`).
* **Negation**: `![tag="NN.*"]` for complement sets.
* **Slop**: `[]{0,3}` allows up to three arbitrary tokens.
* **Word constraints**: `[word="the"]` or `[word!="\."]`.
* **Part‑of‑speech groups**: the grammar loader doesn’t know them, but you
  can embed them in the regex (`NN.*`, `RB.*`, etc.).

Refer to the upstream BCQL guide for the complete syntax; only `[ ]`, `lemma`,
`tag`, `word`, `xpos`, `deprel`, and numeric labels are used by the word‑sketch
engine.

## Troubleshooting

* **No results for a relation** – verify the pattern compiles using the server's
  `/health` endpoint and inspect log output. The webapp allows users to run arbitrary
  BCQL queries.
* **Errors loading grammar** – run `mvn test` or the `RelationsValidationTest`
  class; it exercises the loader and reports invalid definitions.
* **Performance** – simple patterns (2‑3 tokens) are fastest; avoid overly broad
  regexes which may degrade Lucene performance.

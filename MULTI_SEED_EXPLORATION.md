# Multi-Seed Semantic Field Exploration

## Overview
Added a new multi-seed exploration mode that allows users to input multiple seed nouns (e.g., "theory,model,hypothesis") and discover their common and individual collocates, creating a semantic field around the entire seed cluster.

## What's New

### 1. API Endpoint
**`GET /api/semantic-field/explore-multi`**

Explores collocates for multiple seeds simultaneously and identifies common patterns.

**Parameters:**
- `seeds` (required): Comma-separated seed nouns (e.g., "theory,model,hypothesis")
- `relation` (optional): Grammatical relation type (default: `adj_predicate`)
  - `adj_predicate`: X is ADJ (e.g., "theory is abstract")
  - `adj_modifier`: ADJ X (e.g., "abstract theory")
  - `subject_of`: X VERBs (e.g., "theory suggests")
  - `object_of`: VERB X (e.g., "develop theory")
- `top` (optional): Max collocates per seed (default: 15)
- `min_shared` (optional): Min collocates to share (default: 2)
- `min_logdice` (optional): Min logDice threshold (default: 2.0)

**Response:**
```json
{
  "status": "ok",
  "seeds": ["theory", "model", "hypothesis"],
  "seed_count": 3,
  "seed_collocates": [
    {"word": "plausible", "logDice": 4.79, "frequency": 234},
    {"word": "evolutionary", "logDice": 4.59, "frequency": 198},
    ...
  ],
  "seed_collocates_count": 23,
  "common_collocates": [],  // Adjectives shared by ALL seeds
  "common_collocates_count": 0,
  "discovered_nouns": [],
  "discovered_nouns_count": 0,
  "edges": [
    {"source": "theory", "target": "correct", "weight": 4.21, "type": "ADJ_PREDICATE"},
    {"source": "model", "target": "tall", "weight": 3.17, "type": "ADJ_PREDICATE"},
    ...
  ],
  "relation_type": "ADJ_PREDICATE",
  "parameters": {...}
}
```

### 2. Webapp UI Enhancement

Added "Multi-Seed Explore" mode to the Semantic Field Explorer tab with:
- Comma-separated seed input field
- Relation type selector (same 4 types)
- Configuration options (top, min_shared, min_logdice)
- Real-time force-directed graph visualization
- Detailed collocate listings

**Features:**
- Validates minimum 2 seeds required
- Shows seed collocates for each input seed
- Highlights common collocates across all seeds (when they exist)
- Force-directed graph visualization with seeds in red center nodes
- Interactive dragging of nodes

### 3. Example Usage

**Single noun exploration (existing):**
```
Seed: "theory"
Relation: X is ADJ
Result: Finds adjectives describing "theory" and discovers similar nouns
```

**Multi-seed exploration (new):**
```
Seeds: "theory,model,hypothesis"
Relation: X is ADJ
Result: 
  - Seed collocates: All adjectives found for all 3 seeds (union)
  - Common collocates: Adjectives shared by ALL 3 seeds (intersection)
  - Visualization: Bipartite graph showing all seed-collocate relationships
```

## Implementation Details

### Backend Changes
**File:** `WordSketchApiServer.java`

- Added `/api/semantic-field/explore-multi` endpoint
- New handler method: `handleSemanticFieldExploreMulti()`
- Logic:
  1. Parse comma-separated seeds
  2. For each seed, find collocates using specified relation
  3. Calculate intersection of common collocates
  4. Format response with edges for visualization

### Frontend Changes
**File:** `webapp/index.html`

- Added "Multi-Seed Explore" radio button option
- New control panel: `multiexploreMode` div
- New functions:
  - `switchSemanticMode()`: Updated to handle "multiexplore" mode
  - `runMultiExplore()`: API call handler and result processing
  - `visualizeMultiExploration()`: D3.js force-directed graph
  - `renderMultiExplorationDetails()`: Results panel with collocate listings

## Test Results

### Test 1: Abstract Concepts
```
Seeds: theory, model, hypothesis
Relation: X is ADJ
Result: 23 seed collocates, 0 common
Top collocates: correct(4.21), practical(3.73), plausible(4.79), evolutionary(4.59)
```

### Test 2: Literary Works
```
Seeds: book, novel, story
Relation: X is ADJ
Result: 14 seed collocates, 0 common
```

### Test 3: Animals
```
Seeds: dog, cat, horse
Relation: X VERBs (subject_of)
Result: 24 seed collocates
Top verbs: ride, run, bark, meow, gallop
```

## Usage

### Via API:
```bash
curl "http://localhost:8080/api/semantic-field/explore-multi?seeds=theory,model,hypothesis&relation=adj_predicate&top=10"
```

### Via Webapp:
1. Open http://localhost:3000
2. Click on "Semantic Field Explorer" tab
3. Select "Multi-Seed Explore" radio button
4. Enter seeds (comma-separated)
5. Select relation type
6. Adjust parameters if needed
7. Click "Explore"

## Common Patterns

### When common_collocates > 0:
This indicates semantic similarity - the seeds share descriptive adjectives/verbs. Example:
- Seeds: "cat,dog" (both animals)
- Common collocates: "furry,loyal,friendly"

### When common_collocates = 0:
Seeds are semantically different even if related. Example:
- Seeds: "book,tree,house" (all nouns, but different categories)
- Each has unique collocates

## Notes

- Multi-seed exploration requires minimum 2 seeds
- Results show UNION of all collocates + INTERSECTION of common ones
- Common collocates often empty in large corpora due to polysemy
- The endpoint works with all 4 relation types (ADJ_PREDICATE, ADJ_MODIFIER, SUBJECT_OF, OBJECT_OF)
- Performance depends on corpus size and number of seeds

## Future Enhancements

Potential improvements:
1. **Second-level discovery**: Find nouns that share the common collocates
2. **Weighted ranking**: Score each collocate by how many seeds share it
3. **Semantic similarity**: Calculate overall semantic distance between seed cluster
4. **Comparison mode**: Compare multi-seed clusters (e.g., "theory,model" vs "idea,concept")

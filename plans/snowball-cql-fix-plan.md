# Snowball CQL Parsing Fix - Analysis and Plan

## Problem Summary

The Snowball collocation feature is failing with:
```
pl.marcinmilkowski.word_sketch.grammar.CQLParseException: Invalid constraint format: remain
```

When executing the pattern:
```
[word="be|remain|seem|appear|feel|get|become|look|smell|taste"] [tag="JJ.*"]
```

## Root Cause Analysis

### The Issue

The `CQLParser.parseConstraint()` method at [line 253](d:/git/word-sketch-lucene/src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLParser.java#L253) performs:

```java
String[] parts = constraintStr.split("\\|");
```

This **splits on ALL `|` characters**, including those inside quoted strings.

### What's Happening

1. Input constraint: `word="be|remain|seem|appear|feel|get|become|look|smell|taste"`
2. After split by `|`:
   - `parts[0]` = `word="be`
   - `parts[1]` = `remain`
   - `parts[2]` = `seem`
   - etc.
3. The parser tries to parse `remain` as `field=value` format
4. Fails with "Invalid constraint format: remain"

### Two Different Use Cases

The CQL syntax supports **two different uses of `|`**:

1. **OR between constraints** (field-level OR):
   ```
   [tag="JJ"|tag="RB"]
   ```
   Means: tag is JJ OR tag is RB
   
2. **Regex alternatives inside value** (value-level regex):
   ```
   [word="be|remain|seem"]
   ```
   Means: word matches the regex `be|remain|seem`

The current parser doesn't distinguish between these two cases.

## Design Considerations

### Why This Worked Before

Looking at the test cases in [CQLParserTest.java](d:/git/word-sketch-lucene/src/test/java/pl/marcinmilkowski/word_sketch/grammar/CQLParserTest.java), the OR syntax tested is:
```java
[tag="JJ"|tag="RB"]  // Field-level OR
```

The snowball feature is the first to use **regex alternatives inside quoted values**:
```java
[word="be|remain|seem"]  // Value-level regex
```

### The Fix Strategy

We need to modify `parseConstraint()` to:
1. **Respect quoted strings** when splitting by `|`
2. Only split on `|` that appears **outside quotes**
3. Pipes inside quotes should be preserved as part of the pattern value

## Implementation Plan

### Step 1: Add Quote-Aware Splitting Method

Create a helper method in `CQLParser.java` to split by `|` while respecting quotes:

```java
/**
 * Split a string by | character, but ignore | inside quoted strings.
 * Example: 
 *   input: word="be|remain"|tag="VB"
 *   output: ["word=\"be|remain\"", "tag=\"VB\""]
 */
private List<String> splitByOrOutsideQuotes(String str) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuote = false;
    
    for (int i = 0; i < str.length(); i++) {
        char c = str.charAt(i);
        
        if (c == '"') {
            inQuote = !inQuote;
            current.append(c);
        } else if (c == '|' && !inQuote) {
            // Split here - this is an OR operator
            parts.add(current.toString());
            current = new StringBuilder();
        } else {
            current.append(c);
        }
    }
    
    // Add the last part
    if (current.length() > 0) {
        parts.add(current.toString());
    }
    
    return parts;
}
```

### Step 2: Update parseConstraint() Method

Replace line 253 in [CQLParser.java](d:/git/word-sketch-lucene/src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLParser.java#L253):

**Before:**
```java
String[] parts = constraintStr.split("\\|");
```

**After:**
```java
List<String> partsList = splitByOrOutsideQuotes(constraintStr);
String[] parts = partsList.toArray(new String[0]);
```

### Step 3: Add Test Cases

Add tests to [CQLParserTest.java](d:/git/word-sketch-lucene/src/test/java/pl/marcinmilkowski/word_sketch/grammar/CQLParserTest.java) to verify:

1. **Regex pattern with pipes in value:**
   ```java
   @Test
   void testConstraintWithRegexAlternatives() {
       String cql = "[word=\"be|remain|seem\"]";
       CQLPattern pattern = parser.parse(cql);
       
       CQLPattern.PatternElement element = pattern.getElements().get(0);
       CQLPattern.Constraint constraint = element.getConstraint();
       
       assertEquals("word", constraint.getField());
       assertEquals("be|remain|seem", constraint.getPattern());
       assertFalse(constraint.isNegated());
       assertNull(constraint.getOrConstraints()); // No OR - it's a regex
   }
   ```

2. **Complex snowball pattern:**
   ```java
   @Test
   void testSnowballLinkingVerbPattern() {
       String cql = "[word=\"be|remain|seem|appear|feel|get|become|look|smell|taste\"] [tag=\"JJ.*\"]";
       CQLPattern pattern = parser.parse(cql);
       
       assertEquals(2, pattern.getElements().size());
       
       // First element: linking verbs with regex OR
       CQLPattern.Constraint wordConstraint = pattern.getElements().get(0).getConstraint();
       assertEquals("word", wordConstraint.getField());
       assertEquals("be|remain|seem|appear|feel|get|become|look|smell|taste", 
                    wordConstraint.getPattern());
       
       // Second element: adjective tag
       CQLPattern.Constraint tagConstraint = pattern.getElements().get(1).getConstraint();
       assertEquals("tag", tagConstraint.getField());
       assertEquals("JJ.*", tagConstraint.getPattern());
   }
   ```

3. **Ensure existing OR syntax still works:**
   ```java
   @Test
   void testFieldLevelOrStillWorks() {
       String cql = "[tag=\"JJ\"|tag=\"RB\"]";
       CQLPattern pattern = parser.parse(cql);
       
       CQLPattern.Constraint constraint = pattern.getElements().get(0).getConstraint();
       assertNotNull(constraint.getOrConstraints());
       assertEquals(2, constraint.getOrConstraints().size());
   }
   ```

### Step 4: Verify Fix

1. Run all existing tests to ensure no regression:
   ```powershell
   mvn test
   ```

2. Run the snowball command again:
   ```powershell
   java -jar target\word-sketch-lucene-1.0.0.jar snowball --index D:\corpus_74m\index --seeds theory,model,hypothesis --mode linking
   ```

## Corpus Reading Strategy

Since the index cannot be changed (3+ days to regenerate), the fix focuses entirely on:

1. **Query parsing** - Fix CQLParser to properly handle regex patterns in values
2. **Query compilation** - Ensure CQLToLuceneCompiler correctly interprets the parsed pattern
3. **Index reading** - The existing Lucene index reader should work fine once queries are correct

The pattern `[word="be|remain|seem|..."]` will be:
- Parsed as a single constraint with field="word" and pattern="be|remain|..."
- Compiled to a Lucene regex query that matches any of the alternatives
- Executed against the existing index's "word" field

No changes to index structure or indexing logic are needed.

## Files to Modify

1. **[CQLParser.java](d:/git/word-sketch-lucene/src/main/java/pl/marcinmilkowski/word_sketch/grammar/CQLParser.java)**
   - Add `splitByOrOutsideQuotes()` helper method
   - Update `parseConstraint()` to use quote-aware splitting

2. **[CQLParserTest.java](d:/git/word-sketch-lucene/src/test/java/pl/marcinmilkowski/word_sketch/grammar/CQLParserTest.java)**
   - Add test for regex alternatives in values
   - Add test for snowball linking verb pattern
   - Verify existing OR constraint tests still pass

## Expected Outcome

After the fix:
- Snowball command will successfully parse the linking verb pattern
- Existing CQL OR syntax `[tag="JJ"|tag="RB"]` will continue to work
- New regex patterns `[word="be|remain|seem"]` will work correctly
- All existing tests will pass
- Snowball collocation exploration will execute without errors

## Alternative Approaches Considered

### Alternative 1: Change Snowball Pattern Syntax
Instead of fixing the parser, change the snowball pattern to use field-level OR:
```java
[word="be"|word="remain"|word="seem"|...]
```

**Rejected because:**
- More verbose and harder to maintain
- Doesn't match standard regex syntax expectations
- Would require updating all snowball patterns

### Alternative 2: Use Different Attribute
Use a regex-specific syntax like `word~"be|remain"`:

**Rejected because:**
- Requires new syntax invention
- More complex to implement
- Quoted values with pipes is more intuitive

## Risk Assessment

**Low Risk:**
- Change is localized to constraint parsing logic
- Comprehensive test coverage can verify correctness
- No index changes required
- Backwards compatible with existing queries

**Testing Requirements:**
- All existing unit tests must pass
- New tests for regex alternatives must pass
- Integration test with snowball command must succeed
- Manual verification with the 74M corpus index

# Smart Paste Import Bugfix Design

## Overview

The current `PcsoScraperService` has two problems: a fragile jsoup-based scraper that breaks whenever the PCSO website changes, and a single-pass line parser in `importManualResults` that cannot handle inline multi-draw results (e.g., `EZ2 (2D) 2PM: 11-04 5PM: 29-16 9PM: 08-31`). The fix removes all scraping infrastructure entirely and replaces `importManualResults` with a two-pass regex parser that handles crowded, copy-pasted text robustly.

## Glossary

- **Bug_Condition (C)**: The condition that triggers the bug — pasted text contains inline multi-draw results on a single line, or a game name and date on the same line, causing the current single-pass parser to extract zero results.
- **Property (P)**: The desired behavior — all draw time/combo pairs present in the pasted text are extracted and saved, regardless of layout.
- **Preservation**: Existing behaviors that must not change — well-formatted multi-line input, duplicate skipping, STL filtering, number-count validation, Add/Edit/Delete modal flows.
- **importManualResults**: The method in `PcsoScraperService` that parses raw pasted text and saves `OfficialResult` records.
- **Game Context Block**: A logical section of pasted text anchored by a recognized game name, optionally followed by a date, followed by one or more result lines.
- **Inline Multi-Draw**: A single line containing multiple `time: combo` pairs for the same game and date (common in EZ2 and Swertres results).
- **isBugCondition**: Pseudocode predicate that returns true when the input triggers the parsing bug.

## Bug Details

### Bug Condition

The bug manifests when pasted text contains inline multi-draw results on a single line, or when a game name and date appear together on the same line. The current `importManualResults` method processes one line at a time with a single `COMBO_PATTERN` scan, so it either misses all draws on a multi-draw line (because it finds the first combo and stops associating a time) or resets `currentDate` to null when it detects a game name, losing the date that was on the same line.

**Formal Specification:**
```
FUNCTION isBugCondition(input)
  INPUT: input of type String (raw pasted text)
  OUTPUT: boolean

  lines := input.split("\n")
  FOR EACH line IN lines DO
    IF mapToGameId(line) != null AND tryParseDateFromText(line) != null THEN
      RETURN true   -- game+date on same line: date gets lost
    END IF
    IF countInlineTimeCombo(line) > 1 THEN
      RETURN true   -- multiple time:combo pairs on one line: only first (or none) extracted
    END IF
  END FOR
  RETURN false
END FUNCTION
```

### Examples

- Input: `EZ2 (2D) 2PM: 11-04 5PM: 29-16 9PM: 08-31`
  - Expected: 3 results saved (2:00 PM → 11,04 / 5:00 PM → 29,16 / 9:00 PM → 08,31)
  - Actual (buggy): 0 results saved (no draw time associated with any combo)

- Input: `Ultra Lotto 6/58 – March 20, 2026\n29-52-49-55-25-09`
  - Expected: 1 result saved for 2026-03-20 at 9:00 PM
  - Actual (buggy): 1 result saved correctly (this case works)

- Input: `Swertres (3D) March 20, 2026 2PM: 4-1-7 5PM: 2-9-3 9PM: 6-0-5`
  - Expected: 3 results saved for 2026-03-20
  - Actual (buggy): 0 results saved (game+date on same line resets date; multi-draw not parsed)

- Input: `Mega Lotto 6/45\n₱49.5M Jackpot\nMarch 20, 2026\n14-22-31-38-41-45`
  - Expected: 1 result saved (noise line ignored)
  - Actual (buggy): 1 result saved correctly (this case works)

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Well-formatted multi-line results (game → date → numbers on separate lines) must continue to parse and save correctly.
- Duplicate detection: if a result for the same game, date, and draw time already exists, it must be skipped silently.
- STL game lines must be ignored entirely.
- Results with the wrong number count for their game must be rejected (e.g., 5 numbers for a 6-ball game).
- The `/admin/results` POST, GET, and DELETE endpoints must be unaffected.
- The Add/Edit result modal flow must be unaffected.

**Scope:**
All inputs that do NOT contain inline multi-draw lines or game+date-on-same-line patterns are unaffected by this fix. This includes:
- Single-draw games with numbers on a separate line
- Text with noise tokens (jackpot amounts, URLs) on their own lines
- Any input that already parsed correctly before the fix

## Hypothesized Root Cause

1. **Single-pass line iteration**: `importManualResults` iterates lines one at a time. When it encounters a game name, it resets `currentDate = null` if no date is found on that same line — but the date-detection branch only runs when `currentDate == null`, so a date embedded in the game-name line is lost before the combo-search branch can use it.

2. **No inline multi-draw extraction**: The combo-search branch calls `extractTime(text)` once per `COMBO_PATTERN` match, but `extractTime` returns only the first time found in the line. For a line like `2PM: 11-04 5PM: 29-16`, the first match returns `2:00 PM` for both combos, and the second combo (`29-16`) is incorrectly associated with `2:00 PM` — or the time/combo pairing logic fails entirely because `COMBO_PATTERN` matches `11-04 5` as a single combo.

3. **COMBO_PATTERN greediness**: The pattern `\d{1,2}(?:\s*[- ,]\s*\d{1,2}){1,5}` can match across time boundaries (e.g., `04 5` in `11-04 5PM`) producing malformed number strings.

4. **Date reset on game detection**: When `mapToGameId(text) != null`, the code sets `currentDate = tryParseDateFromText(text)` and then does `continue`, skipping the combo-search branch. If the same line also contains draw results (as in a fully inline format), those results are never processed.

## Correctness Properties

Property 1: Bug Condition - Inline Multi-Draw Extraction

_For any_ pasted text where the bug condition holds (isBugCondition returns true — i.e., the text contains at least one line with multiple inline time:combo pairs, or a game+date+combos all on one line), the fixed `importManualResults` function SHALL extract and save every time:combo pair present in that line, producing an `updatedCount` equal to the number of distinct time:combo pairs found.

**Validates: Requirements 2.2, 2.3**

Property 2: Preservation - Well-Formatted Input Unchanged

_For any_ pasted text where the bug condition does NOT hold (isBugCondition returns false — i.e., the text uses the traditional multi-line format with game, date, and numbers on separate lines), the fixed `importManualResults` function SHALL produce the same `updatedCount` and saved records as the original function, preserving all existing parsing behavior for non-buggy inputs.

**Validates: Requirements 3.1, 3.2, 3.5, 3.6**

## Fix Implementation

### Changes Required

**File**: `lotto/src/main/java/com/lotto/service/PcsoScraperService.java`

**Function**: `importManualResults` (rewrite), plus removal of scraping methods

**Specific Changes**:

1. **Remove scraping infrastructure**: Delete `updateResultsFromPcso`, `flattenContent`, `collectElements`, `isHeadingLike`, `looksLikeGameHeading`, `findTimeInTable`, `updateJackpot`, and the `SOURCE_URL` / `USER_AGENT` constants. Remove the jsoup import.

2. **Add two-pass regex constants**:
   ```
   // Pass 1 — game context block header (game name, optional date on same line)
   // Pass 2 — inline draw: captures time token and number combo
   INLINE_DRAW_PATTERN = (\d{1,2}(?::\d{2})?\s*(?:AM|PM))\s*:?\s*(\d{1,2}(?:[-,\s]\d{1,2}){1,5})
   ```
   The existing `COMBO_PATTERN` and `TIME_PATTERN` are retained for single-draw lines.

3. **Rewrite `importManualResults` with two-pass approach**:

   **Pass 1 — build game context blocks**:
   ```
   FOR EACH line IN lines DO
     gid := mapToGameId(line)
     IF gid != null THEN
       start new block: { gameId=gid, date=tryParseDateFromText(line), lines=[] }
     ELSE IF currentBlock != null THEN
       append line to currentBlock.lines
       IF currentBlock.date == null THEN
         currentBlock.date = tryParseDateFromText(line)
       END IF
     END IF
   END FOR
   -- After loop: for any block with null date, set date = today
   ```

   **Pass 2 — extract results from each block**:
   ```
   FOR EACH block IN blocks DO
     date := block.date ?? LocalDate.now().toString()
     FOR EACH line IN block.lines DO
       -- Try inline multi-draw first
       inlineMatches := INLINE_DRAW_PATTERN.findAll(line)
       IF inlineMatches.count > 0 THEN
         FOR EACH match IN inlineMatches DO
           time := normalizeTime(match.group(1))
           numbers := normalizeNumbers(match.group(2))
           IF isValidCount(block.gameId, numbers.count) THEN
             saveResult(block.gameId, date, time, numbers, errors)
           END IF
         END FOR
       ELSE
         -- Fall back to single-draw extraction
         combo := COMBO_PATTERN.find(line)
         IF combo != null AND isValidCount(block.gameId, combo.count) THEN
           time := extractTime(line) ?? (isMultiDraw(block.gameId) ? null : "9:00 PM")
           IF time != null THEN
             saveResult(block.gameId, date, time, normalizeNumbers(combo), errors)
           END IF
         END IF
       END IF
     END FOR
   END FOR
   ```

4. **Regex for inline draw pattern** (Java string):
   ```java
   private static final Pattern INLINE_DRAW_PATTERN = Pattern.compile(
       "(\\d{1,2}(?::\\d{2})?\\s*(?:AM|PM))\\s*:?\\s*(\\d{1,2}(?:[-,\\s]\\d{1,2}){1,5})",
       Pattern.CASE_INSENSITIVE
   );
   ```
   This captures group 1 = time token (e.g., `2PM`, `5:00 PM`) and group 2 = number combo (e.g., `11-04`).

5. **Date fallback**: After Pass 1, any block whose `date` is still null gets `LocalDate.now().toString()` assigned before Pass 2 runs.

6. **Default time for single-draw games**: In the single-draw fallback branch, if `extractTime` returns null and `!isMultiDraw(gameId)`, default to `"9:00 PM"`.

---

**File**: `lotto/pom.xml`

**Specific Changes**:
- Remove the jsoup dependency block:
  ```xml
  <dependency>
      <groupId>org.jsoup</groupId>
      <artifactId>jsoup</artifactId>
      <version>1.17.2</version>
  </dependency>
  ```

---

**File**: `lotto/src/main/java/com/lotto/controller/AdminController.java`

**Specific Changes**:
- Remove the `@PostMapping("/sync-pcso")` endpoint method.
- Remove the `scraperService` field and constructor injection (keep only `importManualResults` delegation, which can be inlined or moved to a leaner service).

---

**File**: `lottosimulator/app/(tabs)/admin.tsx`

**Specific Changes**:
- Remove the `syncing` state variable and `setSyncing`.
- Remove the `handleSync` function.
- Remove the "Sync" `<Pressable>` button from the `headerBtns` view.
- Update the import modal subtitle/hint text from `"Copy text from the PCSO results page and paste below."` to something like `"Paste results from any PCSO results page. Supports multi-draw lines and mixed formats."`.

## Testing Strategy

### Validation Approach

Testing follows two phases: first run exploratory tests against the unfixed code to confirm the root cause, then verify the fix satisfies both correctness properties.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug on unfixed code and confirm the root cause hypotheses.

**Test Plan**: Write unit tests that call `importManualResults` with inline multi-draw input and assert `updatedCount > 0`. Run against the unfixed code — these tests must fail.

**Test Cases**:
1. **Inline EZ2 multi-draw**: Input `"EZ2 (2D)\n2026-03-20\n2PM: 11-04 5PM: 29-16 9PM: 08-31"` → assert `updatedCount == 3` (will fail on unfixed code)
2. **Inline Swertres with date on game line**: Input `"Swertres (3D) March 20, 2026\n2PM: 4-1-7 5PM: 2-9-3 9PM: 6-0-5"` → assert `updatedCount == 3` (will fail on unfixed code)
3. **Fully inline single line**: Input `"EZ2 (2D) March 20, 2026 2PM: 11-04 5PM: 29-16 9PM: 08-31"` → assert `updatedCount == 3` (will fail on unfixed code)
4. **No date fallback**: Input `"Mega Lotto 6/45\n14-22-31-38-41-45"` (no date anywhere) → assert `updatedCount == 1` using today's date (may fail on unfixed code)

**Expected Counterexamples**:
- `updatedCount == 0` for all inline multi-draw inputs
- Root cause confirmed: `COMBO_PATTERN` matches across time boundaries; `extractTime` returns only the first time on the line

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**
```
FOR ALL input WHERE isBugCondition(input) DO
  result := importManualResults_fixed(input)
  ASSERT result.updatedCount == expectedDrawCount(input)
  ASSERT result.errors.isEmpty()
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed function produces the same result as the original function.

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT importManualResults_original(input).updatedCount
       == importManualResults_fixed(input).updatedCount
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many well-formatted input variations automatically
- It catches edge cases (extra whitespace, mixed separators, noise lines) that manual tests miss
- It provides strong guarantees that the two-pass rewrite doesn't regress existing behavior

**Test Cases**:
1. **Well-formatted multi-line preservation**: Observe that `"Ultra Lotto 6/58\n2026-03-20\n29-52-49-55-25-09"` saves 1 result on unfixed code, then assert the same on fixed code.
2. **Duplicate skipping preservation**: Import the same valid input twice; assert second import returns `updatedCount == 0`.
3. **STL filtering preservation**: Input containing `"STL Pares\n2026-03-20\n1-2"` must return `updatedCount == 0`.
4. **Number count validation preservation**: Input `"Mega Lotto 6/45\n2026-03-20\n14-22-31-38-41"` (only 5 numbers) must return `updatedCount == 0`.

### Unit Tests

- Test `importManualResults` with inline EZ2 multi-draw line → 3 results
- Test `importManualResults` with game+date on same line → correct date used
- Test `importManualResults` with no date in input → today's date used
- Test `importManualResults` with noise lines (jackpot amounts, URLs) → noise ignored
- Test `importManualResults` with all supported date formats (`March 20, 2026` / `Mar 20 2026` / `03/20/2026` / `2026-03-20`)
- Test that STL lines produce zero results
- Test that wrong number counts are rejected

### Property-Based Tests

- Generate random valid multi-draw lines for EZ2/Swertres and assert `updatedCount == number of time:combo pairs`
- Generate random well-formatted single-draw inputs and assert fixed parser matches original parser output (preservation)
- Generate random inputs with injected noise tokens and assert result count equals result count of noise-free version
- Generate random inputs with STL game names mixed in and assert STL lines never contribute to `updatedCount`

### Integration Tests

- Full flow: paste multi-draw EZ2 text via `/admin/import-manual` endpoint → verify records in DB
- Full flow: paste text with no date → verify records saved with today's date
- Verify `/admin/sync-pcso` returns 404 after endpoint removal
- Verify Add/Edit/Delete result flows via `/admin/results` are unaffected

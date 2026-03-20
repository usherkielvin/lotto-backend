# Implementation Plan

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - Inline Multi-Draw Extraction
  - **CRITICAL**: This test MUST FAIL on unfixed code — failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior — it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bug exists
  - **Scoped PBT Approach**: Scope the property to the concrete failing cases below for reproducibility
  - Create `lotto/src/test/java/com/lotto/service/PcsoScraperServiceBugConditionTest.java`
  - Test case 1 — inline EZ2 multi-draw (date on separate line):
    - Input: `"EZ2 (2D)\n2026-03-20\n2PM: 11-04 5PM: 29-16 9PM: 08-31"`
    - Assert `updatedCount == 3`
  - Test case 2 — Swertres with game+date on same line:
    - Input: `"Swertres (3D) March 20, 2026\n2PM: 4-1-7 5PM: 2-9-3 9PM: 6-0-5"`
    - Assert `updatedCount == 3`
  - Test case 3 — fully inline single line (game+date+draws):
    - Input: `"EZ2 (2D) March 20, 2026 2PM: 11-04 5PM: 29-16 9PM: 08-31"`
    - Assert `updatedCount == 3`
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests FAIL (updatedCount == 0 — this proves the bug exists)
  - Document counterexamples found (e.g., `COMBO_PATTERN` matches `04 5` across time boundary; `extractTime` returns only first time on line)
  - Mark task complete when tests are written, run, and failure is documented
  - _Requirements: 1.2, 1.3_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Well-Formatted Input Unchanged
  - **IMPORTANT**: Follow observation-first methodology
  - Create `lotto/src/test/java/com/lotto/service/PcsoScraperServicePreservationTest.java`
  - Observe on UNFIXED code:
    - `"Ultra Lotto 6/58\n2026-03-20\n29-52-49-55-25-09"` → `updatedCount == 1`
    - Same input imported twice → second import returns `updatedCount == 0` (duplicate skip)
    - `"STL Pares\n2026-03-20\n1-2"` → `updatedCount == 0` (STL filtered)
    - `"Mega Lotto 6/45\n2026-03-20\n14-22-31-38-41"` (5 numbers) → `updatedCount == 0` (count validation)
  - Write property-based tests asserting these observed behaviors for all non-buggy inputs (isBugCondition returns false)
  - Property: for all well-formatted multi-line inputs (game, date, numbers on separate lines), `updatedCount` equals the number of valid non-duplicate results present
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS (confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.5, 3.6_

- [x] 3. Fix for inline multi-draw parsing bug

  - [x] 3.1 Remove jsoup dependency from pom.xml
    - Remove the jsoup dependency block from `lotto/pom.xml`:
      ```xml
      <dependency>
          <groupId>org.jsoup</groupId>
          <artifactId>jsoup</artifactId>
          <version>1.17.2</version>
      </dependency>
      ```
    - _Requirements: 2.1_

  - [x] 3.2 Rewrite PcsoScraperService
    - Delete all scraping methods: `updateResultsFromPcso`, `flattenContent`, `collectElements`, `isHeadingLike`, `looksLikeGameHeading`, `findTimeInTable`, `updateJackpot`
    - Remove `SOURCE_URL`, `USER_AGENT` constants and all jsoup imports
    - Add `INLINE_DRAW_PATTERN`:
      ```java
      private static final Pattern INLINE_DRAW_PATTERN = Pattern.compile(
          "(\\d{1,2}(?::\\d{2})?\\s*(?:AM|PM))\\s*:?\\s*(\\d{1,2}(?:[-,\\s]\\d{1,2}){1,5})",
          Pattern.CASE_INSENSITIVE
      );
      ```
    - Rewrite `importManualResults` with two-pass approach:
      - Pass 1: build game context blocks — each block holds `{ gameId, date, lines[] }`; when a game is detected, start a new block and try to parse date from the same line; append subsequent lines to the current block; if date is found on a non-game line, store it on the block
      - After Pass 1: for any block whose `date` is still null, set `date = LocalDate.now().toString()`
      - Pass 2: for each block, iterate its lines; try `INLINE_DRAW_PATTERN` first (captures all time:combo pairs on the line); if no inline matches, fall back to `COMBO_PATTERN` + `extractTime` with `"9:00 PM"` default for single-draw games
    - _Bug_Condition: isBugCondition(input) — input contains a line with multiple inline time:combo pairs, or a game+date on the same line_
    - _Expected_Behavior: updatedCount equals the number of distinct time:combo pairs extracted from all matching lines_
    - _Preservation: well-formatted multi-line inputs, duplicate skipping, STL filtering, number-count validation all unchanged_
    - _Requirements: 2.2, 2.3, 2.4, 2.5, 2.6, 3.1, 3.2, 3.5, 3.6_

  - [x] 3.3 Remove /sync-pcso endpoint from AdminController
    - Remove the `@PostMapping("/sync-pcso")` method from `AdminController`
    - Remove the `scraperService` field and its constructor parameter
    - Keep only the `importManualResults` delegation via `scraperService.importManualResults` — inline the call or retain a minimal reference as needed
    - _Requirements: 2.1_

  - [x] 3.4 Remove Sync button from admin.tsx
    - Remove `syncing` state and `setSyncing`
    - Remove `handleSync` function
    - Remove the Sync `<Pressable>` button from `headerBtns`
    - Update import modal subtitle from `"Copy text from the PCSO results page and paste below."` to `"Paste results from any PCSO results page. Supports multi-draw lines and mixed formats."`
    - _Requirements: 2.1_

  - [x] 3.5 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - Inline Multi-Draw Extraction
    - **IMPORTANT**: Re-run the SAME tests from task 1 — do NOT write new tests
    - Run `PcsoScraperServiceBugConditionTest` against the fixed code
    - **EXPECTED OUTCOME**: All three test cases PASS (`updatedCount == 3` for each inline multi-draw input)
    - _Requirements: 2.2, 2.3_

  - [x] 3.6 Verify preservation tests still pass
    - **Property 2: Preservation** - Well-Formatted Input Unchanged
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Run `PcsoScraperServicePreservationTest` against the fixed code
    - **EXPECTED OUTCOME**: All preservation tests PASS (no regressions)
    - Confirm duplicate skipping, STL filtering, and number-count validation are all intact

- [x] 4. Write fix verification tests
  - Create `lotto/src/test/java/com/lotto/service/PcsoScraperServiceFixVerificationTest.java`
  - Test: inline EZ2 multi-draw (date on separate line) → `updatedCount == 3`, results saved with correct times (2:00 PM, 5:00 PM, 9:00 PM) and numbers (11,04 / 29,16 / 08,31)
  - Test: game+date on same line (`"Swertres (3D) March 20, 2026\n2PM: 4-1-7 5PM: 2-9-3 9PM: 6-0-5"`) → `updatedCount == 3`, date is `2026-03-20`
  - Test: no-date fallback (`"Mega Lotto 6/45\n14-22-31-38-41-45"`) → `updatedCount == 1`, `drawDateKey` equals today's date
  - Test: noise tolerance (`"Mega Lotto 6/45\n₱49.5M Jackpot\nhttps://pcso.gov.ph\nMarch 20, 2026\n14-22-31-38-41-45"`) → `updatedCount == 1`
  - Test: all supported date formats (`March 20, 2026` / `Mar 20 2026` / `03/20/2026` / `2026-03-20`) each produce `updatedCount == 1`
  - Test: STL lines produce `updatedCount == 0`
  - Test: wrong number count (5 numbers for 6-ball game) produces `updatedCount == 0`
  - Test: duplicate import returns `updatedCount == 0` on second call
  - _Requirements: 2.2, 2.3, 2.4, 2.5, 2.6, 3.1, 3.2, 3.5, 3.6_

- [x] 5. Checkpoint — Ensure all tests pass
  - Run `mvn test` in `lotto/` and confirm zero failures
  - Confirm `PcsoScraperServiceBugConditionTest` passes (bug fixed)
  - Confirm `PcsoScraperServicePreservationTest` passes (no regressions)
  - Confirm `PcsoScraperServiceFixVerificationTest` passes (all scenarios covered)
  - Ensure the app builds without jsoup on the classpath
  - Ask the user if any questions arise

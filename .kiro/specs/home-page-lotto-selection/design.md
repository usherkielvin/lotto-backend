# Home Page Lotto Selection Bugfix Design

## Overview

Three bugs in `lottosimulator/components/home-screen.tsx` degrade the home page experience:

1. **Flat game list** — all games render in one unsorted grid with no category labels.
2. **Missing jackpots on off-days** — the API call uses `/games?day=today`, so major games not drawing today are excluded from the carousel.
3. **Stale bet builder on game change** — tapping "Bet Now" or a game chip updates `selectedGameId` but the number grid and selection tray don't reliably re-render, and the view doesn't scroll to the bet builder.

The fix is minimal and targeted: change the API endpoint, derive two categorised arrays from the full list, split the game picker into two labelled sections, restrict the jackpot carousel to major games, add a `ScrollView` ref to scroll to the bet builder on game selection, and remove the unused `router` import.

---

## Glossary

- **Bug_Condition (C)**: The set of inputs / states that trigger one of the three defects described above.
- **Property (P)**: The desired correct behaviour when the bug condition holds.
- **Preservation**: Existing behaviours (manual pick, lucky pick, bet placement, balance deduction, official results) that must remain unchanged after the fix.
- **`selectedGameId`**: React state string that identifies the currently active game.
- **`selectedGame`**: `useMemo`-derived `LottoGame` object resolved from `games` and `selectedGameId`.
- **`numberOptions`**: `useMemo`-derived array `[1 … selectedGame.maxNumber]` used to render the number grid.
- **`majorGames`**: Derived array of the five 6-ball lotto games (`ultra-658`, `grand-655`, `super-649`, `mega-645`, `lotto-642`).
- **`smallGames`**: Derived array of the four 2D/3D/4D games (`6digit`, `4digit`, `3d-swertres`, `2d-ez2`).
- **`betBuilderRef`**: A `useRef<View>` attached to the Bet Builder card, used to scroll it into view.

---

## Bug Details

### Bug Condition

The three bugs share a common root: the component was built assuming only today's games are relevant, so it fetches a filtered subset, renders them in a flat list, and never scrolls to the bet builder on selection.

**Formal Specification:**
```
FUNCTION isBugCondition(state)
  INPUT: state = { apiEndpoint, gameId, uiContext }
  OUTPUT: boolean

  // Bug 1 — flat list, no categories
  IF state.apiEndpoint = '/games?day=today'
     AND games rendered in single flat View with no category labels
  THEN RETURN true

  // Bug 2 — missing jackpots on off-days
  IF state.apiEndpoint = '/games?day=today'
     AND today is NOT a draw day for one or more major games
  THEN RETURN true

  // Bug 3 — stale bet builder
  IF state.uiContext IN ['jackpot-carousel-bet-now', 'game-chip-tap']
     AND numberOptions still reflects previous game's maxNumber
     AND view has NOT scrolled to bet builder
  THEN RETURN true

  RETURN false
END FUNCTION
```

### Examples

- **Bug 1**: On any day, the "Choose Lotto Game" section shows `ultra-658`, `2d-ez2`, `4digit`, etc. all in one row-wrapped grid — no "Major Lotto Games" or "3D / 4D" headings.
- **Bug 2**: On a Monday (no Ultra 6/58 draw), the jackpot carousel is empty or missing Ultra 6/58 because `/games?day=today` excludes it.
- **Bug 3**: User taps "Bet Now" on the Grand Lotto 6/55 card; `selectedGameId` becomes `grand-655` but the number grid still shows 1–58 (Ultra's range) because `numberOptions` memo hasn't re-evaluated, or the view stays scrolled at the top.
- **Edge case**: User taps a game chip for `2d-ez2` (maxNumber 31); if the previous game was `ultra-658` (maxNumber 58), stale `numberOptions` would show numbers 32–58 that are invalid for the new game.

---

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Manual number selection (pick exactly 6, toggle on/off) must continue to work for every game.
- Lucky Pick must continue to generate 6 unique numbers within the selected game's valid range.
- Placing a bet must continue to deduct the stake from the demo balance and record the bet against the correct game and draw date.
- The "Latest Official 9:00 PM Result" section must continue to display seeded result numbers for the selected game.
- Draw-day context (draw time, draw days) displayed on game chips must remain unchanged.

**Scope:**
All interactions that do NOT involve the API endpoint change, category rendering, or game-selection scroll are completely unaffected. This includes:
- Stake adjustment (+20 / -20 controls)
- Bet placement flow and balance deduction
- Lucky Pick number generation
- Clock countdown and draw-lock logic
- Official result display

---

## Hypothesized Root Cause

1. **Wrong API endpoint**: `apiFetch('/games?day=today')` filters server-side to only today's scheduled games. Changing to `apiFetch('/games')` returns all games; the backend already supports this endpoint.

2. **No category derivation**: The component renders `games.map(...)` directly with no grouping. Adding `MAJOR_LOTTO_IDS` and `SMALL_GAME_IDS` constant arrays and deriving `majorGames` / `smallGames` via `useMemo` fixes the flat list and lets the carousel be restricted to major games.

3. **No scroll-to-bet-builder on game selection**: `setSelectedGameId` is called but there is no `ScrollView` ref on the outer scroll container, so the view never scrolls down to the bet builder. Adding a `ref` to the outer `ScrollView` and calling `scrollTo` (or using a `View` `measureLayout`) after `setSelectedGameId` fixes this.

4. **`useEffect` clear timing**: `useEffect(() => setSelectedNumbers([]), [selectedGameId])` is correct in principle, but if the component re-renders for an unrelated reason between the state update and the effect, the grid may briefly show stale numbers. This is a minor timing issue; the effect itself is correct and will fire reliably once the API change ensures `games` is stable.

5. **Unused `router` import**: `useRouter` is imported but never used, causing a lint warning. Removing it is a clean-up item.

---

## Correctness Properties

Property 1: Bug Condition — All Games Always Visible With Categories

_For any_ home page load, the fixed component SHALL fetch all games via `/games` (no day filter), derive `majorGames` and `smallGames` from the full list, display the jackpot carousel with exactly the five major lotto games, and render the "Choose Lotto Game" section with two labelled subsections ("Major Lotto Games" and "3D / 4D Games").

**Validates: Requirements 2.1, 2.2**

Property 2: Bug Condition — Game Selection Syncs Bet Builder

_For any_ user action that changes `selectedGameId` (tapping "Bet Now" on a carousel card or tapping a game chip), the fixed component SHALL immediately clear `selectedNumbers`, recompute `numberOptions` to `[1 … newGame.maxNumber]`, and scroll the outer `ScrollView` to the bet builder section.

**Validates: Requirements 2.3, 2.4**

Property 3: Preservation — Existing Bet Flow Unchanged

_For any_ input that does NOT involve the API endpoint, category rendering, or game-selection scroll (stake changes, lucky pick, bet placement, balance display, official results), the fixed component SHALL produce exactly the same behaviour as the original component.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5**

---

## Fix Implementation

### Changes Required

**File**: `lottosimulator/components/home-screen.tsx`

1. **Remove unused import**
   - Delete `useRouter` from the import and the `const router = useRouter()` line.

2. **Change API endpoint**
   - Replace `apiFetch<LottoGame[]>('/games?day=today')` with `apiFetch<LottoGame[]>('/games')`.
   - Keep the `.then` handler that sets `games` and seeds `selectedGameId` with the first major game (`MAJOR_LOTTO_IDS[0]`).

3. **Add category constants and derived arrays**
   ```
   const MAJOR_LOTTO_IDS = ['ultra-658', 'grand-655', 'super-649', 'mega-645', 'lotto-642']
   const SMALL_GAME_IDS  = ['6digit', '4digit', '3d-swertres', '2d-ez2']

   const majorGames = useMemo(
     () => MAJOR_LOTTO_IDS.map(id => games.find(g => g.id === id)).filter(Boolean),
     [games]
   )
   const smallGames = useMemo(
     () => SMALL_GAME_IDS.map(id => games.find(g => g.id === id)).filter(Boolean),
     [games]
   )
   ```

4. **Restrict jackpot carousel to `majorGames`**
   - Replace `games.map(...)` inside the jackpot `ScrollView` with `majorGames.map(...)`.
   - Update the dot-indicator count to use `majorGames.length`.

5. **Add outer ScrollView ref and scroll-to-bet-builder**
   - Add `const scrollViewRef = useRef<ScrollView>(null)` and `const betBuilderRef = useRef<View>(null)`.
   - Attach `ref={scrollViewRef}` to the outer `ScrollView`.
   - Attach `ref={betBuilderRef}` to the `Animated.View` wrapping the Bet Builder card.
   - Create a `selectGame(id: string)` helper that calls `setSelectedGameId(id)` then uses `betBuilderRef.current?.measureLayout` to scroll the outer `ScrollView` to the bet builder's y-offset.
   - Replace all direct `setSelectedGameId(g.id)` calls (in the carousel "Bet Now" press and game chip press) with `selectGame(g.id)`.

6. **Split game picker into two labelled subsections**
   - Replace the single `games.map(...)` grid with two subsections:
     - Label "Major Lotto Games" → `majorGames.map(...)`
     - Label "3D / 4D Games" → `smallGames.map(...)`

---

## Testing Strategy

### Validation Approach

Two-phase approach: first surface counterexamples on the unfixed code to confirm root causes, then verify the fix and run preservation checks.

### Exploratory Bug Condition Checking

**Goal**: Demonstrate the three bugs on the UNFIXED code before applying the fix.

**Test Plan**: Mount `HomeScreen` in a test environment with a mocked `apiFetch` that returns all 9 games. Assert the current broken behaviour to confirm root causes.

**Test Cases**:
1. **Off-day missing jackpot**: Mock `/games?day=today` to return only `[lotto-642]`. Assert that `ultra-658` is absent from the rendered carousel (will pass on unfixed code, confirming Bug 2).
2. **Flat game list**: Render the component and assert that no "Major Lotto Games" or "3D / 4D" heading text exists in the output (will pass on unfixed code, confirming Bug 1).
3. **Stale number grid after game change**: Select `ultra-658` (maxNumber 58), then fire a "Bet Now" press for `lotto-642` (maxNumber 42). Assert that `numberOptions` still contains 43–58 (will pass on unfixed code, confirming Bug 3 timing issue).
4. **No scroll on game change**: After pressing "Bet Now", assert that `scrollViewRef.current?.scrollTo` was NOT called (will pass on unfixed code, confirming missing scroll).

**Expected Counterexamples**:
- Carousel renders fewer than 5 major game cards on off-days.
- No category label text found in the game picker section.
- Number grid contains out-of-range numbers for the newly selected game immediately after selection.

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed component produces the expected behaviour.

**Pseudocode:**
```
FOR ALL state WHERE isBugCondition(state) DO
  result := render HomeScreen_fixed(state)
  ASSERT expectedBehavior(result)
END FOR
```

**Specific assertions after fix:**
- `/games` (no query string) is called on mount.
- Carousel always renders exactly 5 major game cards.
- "Major Lotto Games" and "3D / 4D Games" headings are present.
- After pressing "Bet Now" for `lotto-642`, `numberOptions` length equals 42 and `selectedNumbers` is empty.
- `scrollTo` is called on the outer `ScrollView` after game selection.

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed component behaves identically to the original.

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT HomeScreen_original(input) = HomeScreen_fixed(input)
END FOR
```

**Testing Approach**: Property-based testing is recommended because:
- It generates many random stake values, number selections, and game states automatically.
- It catches edge cases (e.g., selecting 6 numbers then switching games) that manual tests miss.
- It provides strong guarantees that the bet flow is unchanged across all non-buggy inputs.

**Test Cases**:
1. **Stake control preservation**: Generate random sequences of +20/-20 presses; assert final stake is clamped to [20, 500] identically before and after fix.
2. **Lucky Pick preservation**: For each game, assert that Lucky Pick always produces exactly 6 unique numbers within `[1, maxNumber]`.
3. **Bet placement preservation**: Mock a successful POST; assert balance decreases by stake and notice text updates correctly.
4. **Official result preservation**: For each game and date key, assert `buildOfficialNumbers` returns the same seeded result before and after fix.

### Unit Tests

- Test that `majorGames` derived array always contains exactly the 5 IDs in `MAJOR_LOTTO_IDS` order when all games are returned.
- Test that `smallGames` derived array always contains exactly the 4 IDs in `SMALL_GAME_IDS` order.
- Test that pressing "Bet Now" calls `selectGame` which triggers both `setSelectedGameId` and `scrollTo`.
- Test that `useEffect` clears `selectedNumbers` when `selectedGameId` changes.
- Test edge case: if a game ID from `MAJOR_LOTTO_IDS` is missing from the API response, it is silently omitted from `majorGames` (no crash).

### Property-Based Tests

- Generate random subsets of the 9 game IDs returned by the API; assert `majorGames` always equals the intersection with `MAJOR_LOTTO_IDS` in the defined order.
- Generate random `selectedGameId` values; assert `numberOptions` always equals `[1 … selectedGame.maxNumber]` with no gaps or duplicates.
- Generate random sequences of game selections; assert `selectedNumbers` is always `[]` immediately after each `selectedGameId` change.

### Integration Tests

- Full render with real (mocked) API: assert all 5 major game cards appear in the carousel on a simulated off-day.
- Tap "Bet Now" on carousel card → assert number grid updates, selection tray is empty, and view scrolls to bet builder.
- Tap a game chip in "3D / 4D" section → assert number grid updates to the correct `maxNumber` for that game.
- Place a bet end-to-end: select game → pick 6 numbers → place bet → assert balance decreases and notice updates.

# Implementation Plan

- [ ] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - API Filter, Flat List, and Stale Bet Builder
  - **CRITICAL**: This test MUST FAIL on unfixed code — failure confirms the bugs exist
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior — it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate all three bugs exist
  - **Scoped PBT Approach**: Scope each property to the concrete failing cases for reproducibility
  - Mount `HomeScreen` with a mocked `apiFetch` that intercepts `/games?day=today` and returns all 9 games
  - Assert that the API call uses `/games?day=today` (not `/games`) — confirms Bug 2 root cause
  - Assert that no "Major Lotto Games" or "3D / 4D Games" heading text exists in the rendered output — confirms Bug 1
  - Assert that the jackpot carousel renders all 9 games (not just the 5 major ones) — confirms Bug 1 scope
  - Simulate selecting `ultra-658` (maxNumber 58), then fire a "Bet Now" press for `lotto-642` (maxNumber 42); assert `numberOptions` still contains values > 42 immediately after the press — confirms Bug 3 stale grid
  - Assert that `scrollTo` was NOT called on the outer `ScrollView` after game selection — confirms missing scroll (Bug 3)
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS (this is correct — it proves the bugs exist)
  - Document counterexamples found (e.g., "carousel shows 9 cards instead of 5", "no category headings found", "numberOptions[42] = 43 after switching to lotto-642")
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [ ] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Bet Flow, Lucky Pick, Stake Controls, and Official Results
  - **IMPORTANT**: Follow observation-first methodology
  - Observe: stake controls clamp correctly — `changeStake(+20)` from 500 stays at 500, `changeStake(-20)` from 20 stays at 20 on unfixed code
  - Observe: Lucky Pick for `ultra-658` always produces exactly 6 unique numbers in [1, 58] on unfixed code
  - Observe: placing a bet via POST deducts stake from balance and updates notice text on unfixed code
  - Observe: `buildOfficialNumbers(game, key)` returns the same seeded result for the same game + date key on unfixed code
  - Write property-based test: for all stake delta sequences, final stake is always clamped to [20, 500]
  - Write property-based test: for each game, Lucky Pick always produces exactly 6 unique numbers within [1, maxNumber]
  - Write property-based test: for any `selectedGameId` that does NOT satisfy isBugCondition, `numberOptions` always equals [1 … selectedGame.maxNumber] with no gaps or duplicates
  - Write property-based test: for any game + date key pair, `buildOfficialNumbers` is deterministic (same output for same inputs)
  - Verify all tests PASS on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS (this confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [ ] 3. Fix home-page-lotto-selection bugs in `lottosimulator/components/home-screen.tsx`

  - [ ] 3.1 Implement the fix
    - Remove `useRouter` import and `const router = useRouter()` line (unused, causes lint warning)
    - Change `apiFetch<LottoGame[]>('/games?day=today')` to `apiFetch<LottoGame[]>('/games')` so all games always load
    - Seed `selectedGameId` with `MAJOR_LOTTO_IDS[0]` (first major game) instead of `data[0].id` after fetch
    - Add constants at module level: `const MAJOR_LOTTO_IDS = ['ultra-658', 'grand-655', 'super-649', 'mega-645', 'lotto-642']` and `const SMALL_GAME_IDS = ['6digit', '4digit', '3d-swertres', '2d-ez2']`
    - Add `const majorGames = useMemo(() => MAJOR_LOTTO_IDS.map(id => games.find(g => g.id === id)).filter(Boolean), [games])` and equivalent for `smallGames`
    - Add `const scrollViewRef = useRef<ScrollView>(null)` and `const betBuilderRef = useRef<View>(null)`
    - Attach `ref={scrollViewRef}` to the outer `ScrollView` and `ref={betBuilderRef}` to the `Animated.View` wrapping the Bet Builder card
    - Create `selectGame(id: string)` helper: calls `setSelectedGameId(id)` then uses `betBuilderRef.current?.measureLayout` to scroll the outer `ScrollView` to the bet builder's y-offset
    - Replace all `setSelectedGameId(g.id)` calls (carousel "Bet Now" press and game chip press) with `selectGame(g.id)`
    - Replace `games.map(...)` in the jackpot carousel with `majorGames.map(...)` and update dot-indicator count to `majorGames.length`
    - Replace the single `games.map(...)` game picker grid with two labelled subsections: "Major Lotto Games" → `majorGames.map(...)` and "3D / 4D Games" → `smallGames.map(...)`
    - _Bug_Condition: isBugCondition(state) where state.apiEndpoint = '/games?day=today' OR games rendered in flat list with no category labels OR uiContext IN ['jackpot-carousel-bet-now', 'game-chip-tap'] with stale numberOptions_
    - _Expected_Behavior: /games endpoint used; majorGames and smallGames derived; carousel shows exactly 5 major games; two labelled subsections rendered; selectGame clears selectedNumbers, recomputes numberOptions, and scrolls to bet builder_
    - _Preservation: Manual pick, Lucky Pick, bet placement, balance deduction, stake controls, official results, draw-lock logic all unchanged_
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 3.3, 3.4, 3.5_

  - [ ] 3.2 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - API Filter, Flat List, and Stale Bet Builder Fixed
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - The test from task 1 encodes the expected behavior
    - When this test passes, it confirms: `/games` is called (no day filter), carousel shows exactly 5 major game cards, "Major Lotto Games" and "3D / 4D Games" headings are present, `numberOptions` length equals 42 after switching to `lotto-642`, and `scrollTo` is called after game selection
    - Run bug condition exploration test from step 1
    - **EXPECTED OUTCOME**: Test PASSES (confirms all three bugs are fixed)
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [ ] 3.3 Verify preservation tests still pass
    - **Property 2: Preservation** - Bet Flow, Lucky Pick, Stake Controls, and Official Results
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Run preservation property tests from step 2
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions in stake controls, Lucky Pick, bet placement, or official results)
    - Confirm all tests still pass after fix (no regressions)

- [ ] 4. Checkpoint — Ensure all tests pass
  - Ensure all tests pass; ask the user if questions arise.

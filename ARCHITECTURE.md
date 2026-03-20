# Lotto Project - Architecture Overview

Spring Boot 3.4.1 backend for a PCSO lottery simulator with user authentication, multi-game betting, real-time result scraping, and wallet management.

- **Port:** 8099
- **Database:** MySQL (`lottodb`)
- **Auth:** BCrypt password hashing, `X-User-Id` header for protected endpoints
- **CORS:** Allows all origins for `/api/**`
- **Security:** CSRF disabled, all endpoints permit all (demo mode)

---

## Table of Contents

1. [Service Layer](#1-service-layer)
2. [Test Service Layer](#2-test-service-layer)
3. [Controllers](#3-controllers)
4. [Entities](#4-entities)
5. [Repositories](#5-repositories)
6. [PCSO Scraper Flow](#6-pcso-scraper-flow)
7. [Overall Bet Lifecycle](#7-overall-bet-lifecycle)
8. [Game Configuration](#8-game-configuration)
9. [Seed Data](#9-seed-data)

---

## 1. Service Layer

### AuthService

| Method | Description |
|---|---|
| `login(username, password)` | Validates credentials against BCrypt hash, returns session payload |
| `register(username, password, displayName)` | Creates user with initial ₱5,000 balance |
| `demoLogin()` | Returns session for `demo-player` account |
| `buildSession(user, isDemo)` | Constructs response with `userId`, `username`, `displayName`, `role`, `demo` flag |

---

### BetService _(Core Business Logic)_

| Method | Description |
|---|---|
| `placeBet(userId, gameId, numbers, stake)` | Places bet, deducts balance, calculates next draw slot |
| `getActiveBets(userId)` | Returns pending bets (calls `settleIfNeeded` first) |
| `getBetHistory(userId)` | Returns settled bets (won/lost) |
| `getUnclaimedWins(userId)` | Returns winning bets not yet claimed |
| `claimBet(userId, betId)` | Marks winning bet as claimed |
| `settleIfNeeded(userId)` | Lazy settlement — checks if draw time passed, looks up official result, calculates payout |
| `settleByResult(gameId, drawDateKey, drawTime, officialNumbersCsv)` | Called on admin import — re-settles ALL bets for that slot, reverses old payouts, applies new ones |
| `findNextDrawSlot(game, now)` | Calculates next valid draw date/time based on game's draw schedule |
| `computePayoutForGame(gameId, matches, stake)` | Game-aware payout calculation (see table below) |
| `countMatches(picked, official)` | Set intersection for 6-number games; positional for digit games |

**Payout Rates**

| Game | Condition | Multiplier |
|---|---|---|
| 2D / EZ2 | 2 exact matches | 4,000× stake |
| 3D / Swertres | 3 exact matches | 450× stake |
| 4D | 4 exact matches | 10,000× stake |
| 6-number games | 6 matches | 50,000× stake |
| 6-number games | 5 matches | 5,000× stake |
| 6-number games | 4 matches | 500× stake |
| 6-number games | 3 matches | 50× stake |

---

### PcsoScraperService

| Method | Description |
|---|---|
| `importManualResults(rawData)` | Parses pasted PCSO text, extracts game/date/numbers, saves results |
| `saveResult(gameId, drawDateKey, drawTime, numbers, errors)` | Upserts `OfficialResult`, calls `betService.settleByResult` |
| `mapToGameId(text)` | Maps PCSO game names to internal IDs |
| `tryParseDateFromText(text)` | Extracts date from various formats |
| `extractTime(text)` | Parses draw times (2PM, 5PM, 9PM, etc.) |

**Regex Patterns Used**

- `COMBO_PATTERN` — number sequences
- `TIME_PATTERN` — draw times
- `INLINE_DRAW_PATTERN` — `time:numbers` pairs on one line

**Supported Date Formats**

- `March 20, 2026`
- `Mar 20 2026`
- `03/20/2026`
- `2026-03-20`
- Falls back to today's date if none found

**Game Name Mapping**

| PCSO Name | Internal ID |
|---|---|
| Ultra Lotto 6/58 | `ultra-658` |
| Grand Lotto 6/55 | `grand-655` |
| Super Lotto 6/49 | `super-649` |
| Mega Lotto 6/45 | `mega-645` |
| Lotto 6/42 | `lotto-642` |
| 6-Digit Lotto | `6digit` |
| 4-Digit Lotto | `4digit` |
| 3D Lotto / Swertres | `3d-swertres` |
| 2D Lotto / EZ2 | `2d-ez2` |

> STL games are filtered out automatically.

---

### ProfileService

| Method | Description |
|---|---|
| `getProfile(userId)` | Returns user stats: `totalPlays`, `prizesWon`, `bestMatch`, `winRate`, `luckyNumbers` (most frequently picked) |

---

## 2. Test Service Layer

### PcsoScraperServiceBugConditionTest

Tests that **must fail** on unfixed code (encode known bug conditions):

- `inlineEz2MultiDraw_dateOnSeparateLine_shouldReturn3` — multi-draw on one line: `2PM: 11-04 5PM: 29-16 9PM: 08-31`
- `swertresGameAndDateOnSameLine_shouldReturn3` — game + date on same line with multi-draw
- `fullyInlineSingleLine_gameAndDateAndDraws_shouldReturn3` — entire input on one line

### PcsoScraperServiceFixVerificationTest

Tests that **must pass** on fixed code:

- Inline multi-draw extraction (3 results from one line)
- Game + date on same line parsing
- No-date fallback to today
- Noise tolerance (jackpot text, URLs)
- Multiple date format support
- STL filtering
- Wrong number count rejection
- Duplicate import skipping

### PcsoScraperServicePreservationTest

Tests that **must pass on both old and new code** (baseline preservation):

- Well-formatted multi-line input unchanged
- Duplicate skipping
- STL filtering
- Number count validation

---

## 3. Controllers

### AuthController — `/api/auth`

| Method | Endpoint | Description |
|---|---|---|
| POST | `/login` | Returns `{userId, username, displayName, role, demo}` |
| POST | `/register` | Creates account, returns session |
| POST | `/demo` | Returns demo-player session |
| GET | `/hash` | Generates BCrypt hash for a password |

### GameController — `/api/games`

| Method | Endpoint | Description |
|---|---|---|
| GET | `/games` | Lists all games; optional `?day` filter (1–7 or `"today"`) |
| GET | `/games/results` | Returns each game with all draw results and winner counts |

### BetController — `/api/bets`

| Method | Endpoint | Description |
|---|---|---|
| POST | `/bets` | Place bet (requires `X-User-Id` header) |
| GET | `/bets` | Active pending bets |
| GET | `/bets/history` | Settled bet history |
| GET | `/bets/unclaimed` | Winning bets not yet claimed |
| POST | `/bets/claim` | Mark bet as claimed |
| GET | `/bets/balance` | Current balance |
| POST | `/bets/balance` | Deposit / withdraw (min ₱50) |
| GET | `/bets/funding` | Funding transaction history |

### ProfileController — `/api/profile`

| Method | Endpoint | Description |
|---|---|---|
| GET | `/profile` | User stats and lucky numbers |

### AdminController — `/api/admin`

| Method | Endpoint | Description |
|---|---|---|
| POST | `/import-manual` | Import manual results (admin only) |
| POST | `/results` | Add official result (admin only) |
| GET | `/results` | List all official results (admin only) |
| DELETE | `/results/{id}` | Delete result (admin only) |

---

## 4. Entities

### User
`id` · `username` (unique) · `passwordHash` · `displayName` · `role` (user/admin) · `createdAt`

### Balance
`userId` (PK) · `amount` (default ₱5,000)

### Bet
`id` (composite: timestamp + UUID) · `userId` · `gameId` · `gameName` · `numbers` (CSV) · `stake` · `drawDateKey` · `drawTime` · `placedAt` · `status` (pending/won/lost) · `matches` · `payout` · `officialNumbers` · `claimed`

### LottoGame
`id` (PK) · `name` · `maxNumber` · `drawTime` (CSV) · `drawDays` (1–7 CSV) · `jackpot` · `jackpotStatus`

### OfficialResult
`id` (PK) · `gameId` · `drawDateKey` · `drawTime` · `numbers` (CSV) · `createdAt`
Unique constraint: `(gameId, drawDateKey, drawTime)`

### FundingTransaction
`id` (PK) · `userId` · `type` (deposit/withdraw) · `amount` · `balanceAfter` · `createdAt`

---

## 5. Repositories

### UserRepository
- `findByUsername(username)` → `Optional<User>`

### BalanceRepository
- Standard CRUD

### BetRepository
- `findByUserIdOrderByPlacedAtDesc(userId)`
- `findByUserIdAndStatusOrderByPlacedAtDesc(userId, status)`
- `findByUserIdAndStatusNotOrderByPlacedAtDesc(userId, status)`
- `findByUserIdAndStatusAndClaimedFalseOrderByPlacedAtDesc(userId, status)`
- `countByGameIdAndDrawDateKeyAndDrawTimeAndStatus(...)`
- `findByGameIdAndDrawDateKeyAndDrawTimeAndStatus(...)`
- `findPendingByGameDateTimeIgnoreCase(gameId, drawDateKey, drawTime)` — case-insensitive time match
- `findByGameIdAndDrawDateKeyAndDrawTimeAndStatusIn(...)` — re-settle already-settled bets

### LottoGameRepository
- Standard CRUD

### OfficialResultRepository
- `findByGameIdAndDrawDateKey(gameId, drawDateKey)`
- `findByGameIdAndDrawDateKeyAndDrawTime(gameId, drawDateKey, drawTime)`
- `findByGameIdOrderByDrawDateKeyDesc(gameId)`

### FundingTransactionRepository
- `findByUserIdOrderByIdDesc(userId)`

---

## 6. PCSO Scraper Flow

```
Input: Pasted PCSO text (any format)
         │
         ▼
Split by newlines → build game context blocks
(game name → date → draw lines)
         │
         ▼
For each block:
  1. Extract gameId via mapToGameId()
  2. Parse date (or fall back to today)
  3. Search for inline time:combo pairs (INLINE_DRAW_PATTERN) first
  4. If no inline matches → fall back to single combo extraction
  5. Normalize numbers (remove separators → CSV)
  6. Validate count per game type
  7. Extract draw time
  8. Call saveResult()
         │
         ▼
saveResult() → upsert OfficialResult → betService.settleByResult()
         │
         ▼
Output: { updatedCount, errors, status }
```

**Key behaviors:**
- Handles multi-draw games (2D/3D with 2PM / 5PM / 9PM)
- Case-insensitive time matching
- Automatic re-settlement when a result changes
- Reverses old payouts before applying new ones

---

## 7. Overall Bet Lifecycle

**Step 1 — User Places Bet**
- `POST /api/bets` with `gameId`, `numbers`, `stake`
- `BetService.placeBet` validates balance, calculates next draw slot
- Creates `Bet` record (`status=pending`), deducts stake from balance

**Step 2 — Draw Time Passes**
- User fetches active bets: `GET /api/bets`
- `BetService.getActiveBets` calls `settleIfNeeded`
- For each pending bet, checks if draw time has passed
- Looks up `OfficialResult` in DB (no seeded fallback)
- If found: calculates matches and payout, updates bet status (`won`/`lost`), credits payout to balance

**Step 3 — Admin Imports Official Result**
- `POST /api/admin/import-manual` with raw PCSO text
- `PcsoScraperService.importManualResults` parses text
- For each result: calls `saveResult` → upserts `OfficialResult` (always overwrites)
- Calls `BetService.settleByResult` → finds ALL bets for that slot (pending + already-settled)
- Reverses old payouts, recalculates with new numbers, updates all bet records and balances

**Step 4 — User Claims Winning Bet**
- `POST /api/bets/claim` with `betId`
- `BetService.claimBet` sets `bet.claimed = true`

---

## 8. Game Configuration

| Game ID | Name | Max # | Draw Times | Draw Days | Top Payout |
|---|---|---|---|---|---|
| `ultra-658` | Ultra Lotto 6/58 | 58 | 9PM | Tue, Fri, Sun | 50,000× |
| `grand-655` | Grand Lotto 6/55 | 55 | 9PM | Mon, Wed, Sat | 5,000× |
| `super-649` | Super Lotto 6/49 | 49 | 9PM | Tue, Thu, Sun | 500× |
| `mega-645` | Mega Lotto 6/45 | 45 | 9PM | Mon, Wed, Fri | 50× |
| `lotto-642` | Lotto 6/42 | 42 | 9PM | Tue, Thu, Sat | — |
| `6digit` | 6-Digit Lotto | 9 | 9PM | Tue, Thu, Sat | — |
| `4digit` | 4-Digit Lotto | 9999 | 9PM | Mon, Wed, Fri | — |
| `3d-swertres` | 3D Lotto (Swertres) | 999 | 2PM, 5PM, 9PM | Daily | 450× |
| `2d-ez2` | 2D Lotto (EZ2) | 45 | 2PM, 5PM, 9PM | Daily | 4,000× |

---

## 9. Seed Data

| Account | Role | Starting Balance |
|---|---|---|
| `demo-player` | user | ₱5,000 |
| `admin` | admin | ₱999,999 |

9 lotto games pre-configured with draw schedules and jackpots via `schema.sql`.

**DB Migrations (auto-applied on startup):**
- Adds `claimed` column to `bets`
- Fixes `official_results` unique key to include `draw_time`

# Bugfix Requirements Document

## Introduction

The PCSO Lotto Simulator home page has three related issues affecting usability:

1. The "Choose Lotto Game" section shows all games in a flat, unsorted list with no category separation, making it hard to distinguish major 6-ball lotto games from the smaller 2D/3D/4D games.
2. The jackpot carousel ("Tonight's Jackpots") only fetches games scheduled for today (`/games?day=today`), so major lotto games that don't draw today are invisible — users can't see or bet on them from the home page on off-days.
3. When a user taps "Bet Now" on a jackpot card or selects a game chip, the bet builder (number grid and selection tray) does not reliably update to reflect the newly selected game's `maxNumber`, causing stale number grids and broken bet slip state.

---

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN the home page loads THEN the system displays all lotto games in a single flat list with no category labels, mixing major 6-ball games and 2D/3D/4D games together.

1.2 WHEN the current day is not a draw day for a major lotto game (e.g., Ultra 6/58 on a Monday) THEN the system omits that game from the jackpot carousel entirely, making its jackpot invisible to the user.

1.3 WHEN the user taps "Bet Now" on a jackpot carousel card THEN the system updates `selectedGameId` but the number grid below does not always re-render with the correct range of numbers for the newly selected game.

1.4 WHEN the user taps a game chip in the "Choose Lotto Game" section THEN the system updates `selectedGameId` but the selection tray and number grid may still reflect the previously selected game's number range.

### Expected Behavior (Correct)

2.1 WHEN the home page loads THEN the system SHALL display the "Choose Lotto Game" section with two clearly labeled categories: "Major Lotto Games" (ultra-658, grand-655, super-649, mega-645, lotto-642) and "3D/4D Games" (3d-swertres, 2d-ez2, 4digit, 6digit).

2.2 WHEN the home page loads THEN the system SHALL always display all five major lotto games in the jackpot carousel regardless of whether they have a draw scheduled for today.

2.3 WHEN the user taps "Bet Now" on a jackpot carousel card THEN the system SHALL immediately update the number grid to show numbers 1 through the selected game's `maxNumber` and clear any previously selected numbers.

2.4 WHEN the user taps a game chip in the "Choose Lotto Game" section THEN the system SHALL immediately update the number grid and selection tray to reflect the newly selected game's valid number range.

### Unchanged Behavior (Regression Prevention)

3.1 WHEN the user selects a game and picks numbers manually THEN the system SHALL CONTINUE TO allow selecting exactly 6 numbers and placing a bet for the 9:00 PM draw.

3.2 WHEN the user uses Lucky Pick THEN the system SHALL CONTINUE TO generate 6 unique random numbers within the selected game's valid range.

3.3 WHEN the home page loads on a day when some games do draw THEN the system SHALL CONTINUE TO show those games' draw-day context correctly in the jackpot carousel.

3.4 WHEN the user places a bet THEN the system SHALL CONTINUE TO deduct the stake from the demo balance and record the bet against the correct game and draw date.

3.5 WHEN the user views the "Latest Official 9:00 PM Result" section THEN the system SHALL CONTINUE TO display the seeded result numbers for the currently selected game.

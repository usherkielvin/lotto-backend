# Bugfix Requirements Document

## Introduction

The app currently relies on a web scraper (`PcsoScraperService.updateResultsFromPcso`) using jsoup to fetch PCSO lotto results from an external website. This scraper is fragile — it breaks whenever the target site changes its HTML structure, is blocked by anti-bot measures, or returns unexpected content. The "Sync" button in the admin UI is effectively broken/unreliable.

The fix removes all scraping functionality entirely and replaces it with a robust manual import system. The `importManualResults` method is upgraded with a smarter regex parser that can handle sloppy, crowded, copy-pasted text from any PCSO results page — tolerating noise, missing fields, varied date formats, and inline multi-draw results on a single line.

---

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN the admin clicks "Sync" THEN the system attempts to scrape an external URL using jsoup, which fails silently or with errors when the site structure changes or blocks the request.

1.2 WHEN the admin pastes multi-draw results on a single line (e.g., `EZ2 (2D) 2PM: 11-04 5PM: 29-16 9PM: 08-31`) THEN the system fails to extract any draw results because the parser only processes one draw time per line.

1.3 WHEN the admin pastes text where the game name and date appear on the same line (e.g., `Ultra Lotto 6/58 – March 20, 2026`) THEN the system may fail to detect the date because it resets `currentDate` to null after detecting the game.

1.4 WHEN the admin pastes text with noise (jackpot amounts, URLs, navigation text, ads) THEN the system may misidentify noise tokens as game names or dates, producing incorrect or zero results.

1.5 WHEN the admin pastes text with a date in a non-standard format (e.g., `03/20/2026` or `2026-03-20`) THEN the system may fail to parse the date and skip all results for that game.

1.6 WHEN the admin pastes text for a single-draw game with no draw time present THEN the system correctly defaults to `9:00 PM`, but for multi-draw games with no time present it silently drops the result instead of reporting an error.

### Expected Behavior (Correct)

2.1 WHEN the admin clicks "Sync" THEN the system SHALL do nothing (the endpoint and button are removed), eliminating the source of scraping failures.

2.2 WHEN the admin pastes multi-draw results on a single line (e.g., `EZ2 (2D) 2PM: 11-04 5PM: 29-16 9PM: 08-31`) THEN the system SHALL extract all draw times and their corresponding number combinations from that single line.

2.3 WHEN the admin pastes text where the game name and date appear on the same line THEN the system SHALL detect both the game name and the date from that line without losing either.

2.4 WHEN the admin pastes text containing noise (jackpot amounts like `₱49.5M`, URLs, navigation text) THEN the system SHALL ignore the noise and continue extracting valid game results.

2.5 WHEN the admin pastes text with a date in any common format (`March 20, 2026` / `Mar 20 2026` / `03/20/2026` / `2026-03-20`) THEN the system SHALL successfully parse the date and associate it with the results.

2.6 WHEN no date is found anywhere in the pasted text THEN the system SHALL fall back to today's date rather than dropping the results.

### Unchanged Behavior (Regression Prevention)

3.1 WHEN the admin pastes well-formatted multi-line results (game on one line, date on next, numbers on next) THEN the system SHALL CONTINUE TO parse and save them correctly.

3.2 WHEN a result for the same game, date, and draw time already exists in the database THEN the system SHALL CONTINUE TO skip the duplicate without error.

3.3 WHEN the admin uses the Add/Edit result modal to manually enter a single result THEN the system SHALL CONTINUE TO save it correctly via the `/admin/results` POST endpoint.

3.4 WHEN the admin deletes a result THEN the system SHALL CONTINUE TO remove it from the database correctly.

3.5 WHEN the pasted text contains STL game references THEN the system SHALL CONTINUE TO ignore them (STL games are not supported).

3.6 WHEN the admin pastes a valid 6-ball lotto result (e.g., `29-52-49-55-25-09`) THEN the system SHALL CONTINUE TO validate that exactly 6 numbers are present before saving.

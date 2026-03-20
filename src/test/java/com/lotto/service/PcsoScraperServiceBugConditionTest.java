package com.lotto.service;

import com.lotto.entity.OfficialResult;
import com.lotto.repository.LottoGameRepository;
import com.lotto.repository.OfficialResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bug condition exploration tests for PcsoScraperService.importManualResults.
 *
 * These tests MUST FAIL on unfixed code — failure confirms the bug exists.
 * They encode the expected (correct) behavior and will pass once the fix is applied.
 *
 * Bug condition (isBugCondition = true):
 *   - Input contains a line with multiple inline time:combo pairs, OR
 *   - Input contains a line where game name and date appear together
 *
 * Counterexamples found on unfixed code (confirmed by running tests):
 *   - Test 1 (inline EZ2 multi-draw): updatedCount == 1 instead of 3.
 *     Actual: only "08-31" at "2:00 PM" was saved (the last COMBO_PATTERN match).
 *     Root cause: COMBO_PATTERN greedily matches across time boundaries — e.g.,
 *     "11-04 5" in "11-04 5PM: 29-16" is consumed as a single combo token,
 *     producing malformed number strings that fail isValidCount. Only the last
 *     combo "08-31" survives. extractTime returns only the first time on the
 *     line ("2:00 PM"), so even the one saved result has the wrong time.
 *
 *   - Test 2 (Swertres game+date on same line): updatedCount == 1 instead of 3.
 *     Actual: only "6-0-5" at "2:00 PM" was saved.
 *     Root cause: Same COMBO_PATTERN greediness on the multi-draw line
 *     "2PM: 4-1-7 5PM: 2-9-3 9PM: 6-0-5". The date IS correctly parsed from
 *     the game line, but the single-pass loop only extracts one result because
 *     COMBO_PATTERN matches "4-1-7 5" and "2-9-3 9" as malformed combos
 *     (wrong count), leaving only "6-0-5" as valid. extractTime returns "2:00 PM"
 *     for all matches instead of the correct per-combo time.
 *
 *   - Test 3 (fully inline single line): updatedCount == 0 instead of 3.
 *     Actual: zero results saved.
 *     Root cause: The entire input is one line. mapToGameId detects the game,
 *     sets currentDate from the same line, then does `continue` — skipping the
 *     combo-search branch entirely. The draw results on that same line are never
 *     processed at all.
 */
class PcsoScraperServiceBugConditionTest {

    private OfficialResultRepository resultRepo;
    private LottoGameRepository gameRepo;
    private PcsoScraperService service;

    @BeforeEach
    void setUp() {
        resultRepo = mock(OfficialResultRepository.class);
        gameRepo = mock(LottoGameRepository.class);

        // No duplicates — every save attempt is a new record
        when(resultRepo.findByGameIdAndDrawDateKeyAndDrawTime(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        // save() returns a new OfficialResult (simulates DB persistence)
        when(resultRepo.save(any())).thenReturn(new OfficialResult());

        service = new PcsoScraperService(resultRepo, gameRepo);
    }

    /**
     * Test 1 — Inline EZ2 multi-draw (date on separate line).
     *
     * The draw results are all on one line: "2PM: 11-04 5PM: 29-16 9PM: 08-31"
     * The unfixed parser cannot extract multiple time:combo pairs from a single line.
     *
     * Expected (correct): updatedCount == 3
     * Actual (buggy):     updatedCount == 0
     */
    @Test
    void inlineEz2MultiDraw_dateOnSeparateLine_shouldReturn3() {
        String rawData = "EZ2 (2D)\n2026-03-20\n2PM: 11-04 5PM: 29-16 9PM: 08-31";

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(3, updatedCount,
                "Expected 3 results for inline EZ2 multi-draw (2PM, 5PM, 9PM) but got " + updatedCount);
    }

    /**
     * Test 2 — Swertres with game+date on same line.
     *
     * The game name and date appear together: "Swertres (3D) March 20, 2026"
     * The unfixed parser resets currentDate when it detects a game name, losing
     * the date embedded on the same line. Even if the date is preserved, the
     * multi-draw line is not parsed correctly.
     *
     * Expected (correct): updatedCount == 3
     * Actual (buggy):     updatedCount == 0
     */
    @Test
    void swertresGameAndDateOnSameLine_shouldReturn3() {
        String rawData = "Swertres (3D) March 20, 2026\n2PM: 4-1-7 5PM: 2-9-3 9PM: 6-0-5";

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(3, updatedCount,
                "Expected 3 results for Swertres with game+date on same line but got " + updatedCount);
    }

    /**
     * Test 3 — Fully inline single line (game+date+draws all on one line).
     *
     * The entire input is a single line. The unfixed parser detects the game name,
     * sets currentDate, then does `continue` — skipping the combo-search branch
     * entirely. No results are ever extracted.
     *
     * Expected (correct): updatedCount == 3
     * Actual (buggy):     updatedCount == 0
     */
    @Test
    void fullyInlineSingleLine_gameAndDateAndDraws_shouldReturn3() {
        String rawData = "EZ2 (2D) March 20, 2026 2PM: 11-04 5PM: 29-16 9PM: 08-31";

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(3, updatedCount,
                "Expected 3 results for fully inline EZ2 line (game+date+draws) but got " + updatedCount);
    }
}

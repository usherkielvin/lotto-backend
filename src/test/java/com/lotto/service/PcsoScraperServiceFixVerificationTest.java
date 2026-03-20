package com.lotto.service;

import com.lotto.entity.OfficialResult;
import com.lotto.repository.LottoGameRepository;
import com.lotto.repository.OfficialResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Fix verification tests for PcsoScraperService.importManualResults.
 *
 * These tests verify the fix works correctly for all scenarios.
 * All tests MUST PASS on the fixed code.
 *
 * Validates: Requirements 2.2, 2.3, 2.4, 2.5, 2.6, 3.1, 3.2, 3.5, 3.6
 */
class PcsoScraperServiceFixVerificationTest {

    private OfficialResultRepository resultRepo;
    private LottoGameRepository gameRepo;
    private PcsoScraperService service;

    @BeforeEach
    void setUp() {
        resultRepo = mock(OfficialResultRepository.class);
        gameRepo = mock(LottoGameRepository.class);

        // Default: no duplicates
        when(resultRepo.findByGameIdAndDrawDateKeyAndDrawTime(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        when(resultRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new PcsoScraperService(resultRepo, gameRepo);
    }

    /**
     * Test 1 — Inline EZ2 multi-draw (date on separate line).
     *
     * Verifies updatedCount == 3 and that each saved result has the correct
     * draw time and numbers.
     */
    @Test
    void inlineEz2MultiDraw_dateOnSeparateLine_savesThreeCorrectResults() {
        String rawData = "EZ2 (2D)\n2026-03-20\n2PM: 11-04 5PM: 29-16 9PM: 08-31";

        ArgumentCaptor<OfficialResult> captor = ArgumentCaptor.forClass(OfficialResult.class);

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(3, updatedCount, "Expected 3 results for inline EZ2 multi-draw");

        verify(resultRepo, times(3)).save(captor.capture());
        List<OfficialResult> saved = captor.getAllValues();

        // Sort by draw time for deterministic assertions
        saved.sort((a, b) -> a.getDrawTime().compareTo(b.getDrawTime()));

        assertEquals("2:00 PM", saved.get(0).getDrawTime());
        assertEquals("11,04",   saved.get(0).getNumbers());

        assertEquals("5:00 PM", saved.get(1).getDrawTime());
        assertEquals("29,16",   saved.get(1).getNumbers());

        assertEquals("9:00 PM", saved.get(2).getDrawTime());
        assertEquals("08,31",   saved.get(2).getNumbers());
    }

    /**
     * Test 2 — Game+date on same line (Swertres).
     *
     * Verifies updatedCount == 3 and that the date is correctly parsed from
     * the game-name line.
     */
    @Test
    void swertresGameAndDateOnSameLine_savesThreeResultsWithCorrectDate() {
        String rawData = "Swertres (3D) March 20, 2026\n2PM: 4-1-7 5PM: 2-9-3 9PM: 6-0-5";

        ArgumentCaptor<OfficialResult> captor = ArgumentCaptor.forClass(OfficialResult.class);

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(3, updatedCount, "Expected 3 results for Swertres with game+date on same line");

        verify(resultRepo, times(3)).save(captor.capture());
        List<OfficialResult> saved = captor.getAllValues();

        // All three results must carry the date parsed from the game line
        for (OfficialResult r : saved) {
            assertEquals("2026-03-20", r.getDrawDateKey(),
                    "Expected date 2026-03-20 but got " + r.getDrawDateKey());
        }
    }

    /**
     * Test 3 — No-date fallback.
     *
     * When no date is present in the input, drawDateKey must equal today's date.
     */
    @Test
    void noDateInInput_fallsBackToToday() {
        String rawData = "Mega Lotto 6/45\n14-22-31-38-41-45";

        ArgumentCaptor<OfficialResult> captor = ArgumentCaptor.forClass(OfficialResult.class);

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(1, updatedCount, "Expected 1 result for Mega Lotto 6/45 without date");

        verify(resultRepo, times(1)).save(captor.capture());
        OfficialResult saved = captor.getValue();

        assertEquals(LocalDate.now().toString(), saved.getDrawDateKey(),
                "Expected drawDateKey to be today's date");
    }

    /**
     * Test 4 — Noise tolerance.
     *
     * Jackpot text and URLs between the game line and the numbers must be ignored.
     */
    @Test
    void noisyInput_jackpotAndUrl_shouldReturn1() {
        String rawData = "Mega Lotto 6/45\n₱49.5M Jackpot\nhttps://pcso.gov.ph\nMarch 20, 2026\n14-22-31-38-41-45";

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(1, updatedCount, "Expected 1 result despite noise lines");
    }

    /**
     * Test 5 — Date format: "March 20, 2026" (long month name).
     */
    @Test
    void dateFormat_longMonthName_shouldReturn1() {
        String rawData = "Mega Lotto 6/45\nMarch 20, 2026\n14-22-31-38-41-45";

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(1, updatedCount, "Expected 1 result for 'March 20, 2026' date format");
    }

    /**
     * Test 6 — Date format: "Mar 20 2026" (abbreviated month, no comma).
     */
    @Test
    void dateFormat_abbreviatedMonthNoComma_shouldReturn1() {
        String rawData = "Mega Lotto 6/45\nMar 20 2026\n14-22-31-38-41-45";

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(1, updatedCount, "Expected 1 result for 'Mar 20 2026' date format");
    }

    /**
     * Test 7 — Date format: "03/20/2026" (US slash format).
     */
    @Test
    void dateFormat_usSlashFormat_shouldReturn1() {
        String rawData = "Mega Lotto 6/45\n03/20/2026\n14-22-31-38-41-45";

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(1, updatedCount, "Expected 1 result for '03/20/2026' date format");
    }

    /**
     * Test 8 — Date format: "2026-03-20" (ISO format).
     */
    @Test
    void dateFormat_isoFormat_shouldReturn1() {
        String rawData = "Mega Lotto 6/45\n2026-03-20\n14-22-31-38-41-45";

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(1, updatedCount, "Expected 1 result for '2026-03-20' date format");
    }

    /**
     * Test 9 — STL lines produce updatedCount == 0.
     */
    @Test
    void stlGame_shouldBeFilteredOut_returnsZero() {
        String rawData = "STL Pares\n2026-03-20\n1-2";

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(0, updatedCount, "STL game should be filtered out");
    }

    /**
     * Test 10 — Wrong number count (5 numbers for a 6-ball game).
     */
    @Test
    void wrongNumberCount_5NumbersFor6BallGame_shouldReturn0() {
        String rawData = "Mega Lotto 6/45\n2026-03-20\n14-22-31-38-41";

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(0, updatedCount, "5 numbers for a 6-ball game should be rejected");
    }

    /**
     * Test 11 — Duplicate import.
     *
     * The second call with the same input must return updatedCount == 0.
     */
    @Test
    void duplicateImport_secondCallReturnsZero() {
        String rawData = "Mega Lotto 6/45\n2026-03-20\n14-22-31-38-41-45";

        // First import succeeds
        Map<String, Object> first = service.importManualResults(rawData);
        assertEquals(1, (int) first.get("updatedCount"), "First import should save 1 result");

        // Simulate the record now existing in the DB
        OfficialResult existing = new OfficialResult();
        existing.setGameId("mega-645");
        existing.setDrawDateKey("2026-03-20");
        existing.setDrawTime("9:00 PM");
        existing.setNumbers("14,22,31,38,41,45");
        when(resultRepo.findByGameIdAndDrawDateKeyAndDrawTime(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(existing));

        // Second import must be skipped
        Map<String, Object> second = service.importManualResults(rawData);
        int updatedCount = (int) second.get("updatedCount");

        assertEquals(0, updatedCount, "Duplicate import should return updatedCount == 0");
    }
}

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
 * Preservation property tests for PcsoScraperService.importManualResults.
 *
 * These tests encode behaviors that MUST NOT change after the fix.
 * They MUST PASS on the current unfixed code — this establishes the baseline.
 *
 * Property 2: Preservation — Well-Formatted Input Unchanged
 * For any pasted text where isBugCondition returns false (traditional multi-line
 * format with game, date, and numbers on separate lines), importManualResults
 * SHALL produce the same updatedCount and saved records as before the fix.
 *
 * Validates: Requirements 3.1, 3.2, 3.5, 3.6
 */
class PcsoScraperServicePreservationTest {

    private OfficialResultRepository resultRepo;
    private LottoGameRepository gameRepo;
    private PcsoScraperService service;

    @BeforeEach
    void setUp() {
        resultRepo = mock(OfficialResultRepository.class);
        gameRepo = mock(LottoGameRepository.class);

        // Default: no duplicates — every save attempt is a new record
        when(resultRepo.findByGameIdAndDrawDateKeyAndDrawTime(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        // save() returns a new OfficialResult (simulates DB persistence)
        when(resultRepo.save(any())).thenReturn(new OfficialResult());

        service = new PcsoScraperService(resultRepo, gameRepo);
    }

    /**
     * Test 1 — Well-formatted multi-line Ultra Lotto 6/58.
     *
     * Game, date, and numbers are each on their own line — the canonical format.
     * This is NOT a bug condition (isBugCondition = false).
     *
     * Expected: updatedCount == 1 (one result saved)
     */
    @Test
    void wellFormattedMultiLine_ultraLotto658_shouldReturn1() {
        String rawData = "Ultra Lotto 6/58\n2026-03-20\n29-52-49-55-25-09";

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(1, updatedCount,
                "Expected 1 result for well-formatted Ultra Lotto 6/58 but got " + updatedCount);
    }

    /**
     * Test 2 — Duplicate skipping.
     *
     * Importing the same valid input twice must skip the second import.
     * The repository stub returns an existing record on the second call,
     * simulating the duplicate-detection path in saveResult.
     *
     * Expected: second import returns updatedCount == 0
     */
    @Test
    void duplicateImport_secondImportShouldReturn0() {
        String rawData = "Ultra Lotto 6/58\n2026-03-20\n29-52-49-55-25-09";

        // First import: no existing record
        when(resultRepo.findByGameIdAndDrawDateKeyAndDrawTime(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        Map<String, Object> firstResult = service.importManualResults(rawData);
        assertEquals(1, (int) firstResult.get("updatedCount"),
                "First import should save 1 result");

        // Second import: record already exists — simulate duplicate
        OfficialResult existing = new OfficialResult();
        existing.setGameId("ultra-658");
        existing.setDrawDateKey("2026-03-20");
        existing.setDrawTime("9:00 PM");
        existing.setNumbers("29,52,49,55,25,09");
        when(resultRepo.findByGameIdAndDrawDateKeyAndDrawTime(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(existing));

        Map<String, Object> secondResult = service.importManualResults(rawData);
        int updatedCount = (int) secondResult.get("updatedCount");

        assertEquals(0, updatedCount,
                "Second import of duplicate should return updatedCount == 0 but got " + updatedCount);
    }

    /**
     * Test 3 — STL filtering.
     *
     * STL game lines must be ignored entirely — mapToGameId returns null for STL.
     * No results should be saved.
     *
     * Expected: updatedCount == 0
     */
    @Test
    void stlGame_shouldBeFilteredOut_returnsZero() {
        String rawData = "STL Pares\n2026-03-20\n1-2";

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(0, updatedCount,
                "STL game input should be filtered out (updatedCount == 0) but got " + updatedCount);
    }

    /**
     * Test 4 — Number count validation.
     *
     * Mega Lotto 6/45 requires exactly 6 numbers. A combo with only 5 numbers
     * must be rejected by isValidCount.
     *
     * Expected: updatedCount == 0
     */
    @Test
    void wrongNumberCount_megaLotto645With5Numbers_shouldReturn0() {
        String rawData = "Mega Lotto 6/45\n2026-03-20\n14-22-31-38-41";

        Map<String, Object> result = service.importManualResults(rawData);
        int updatedCount = (int) result.get("updatedCount");

        assertEquals(0, updatedCount,
                "Mega Lotto 6/45 with only 5 numbers should be rejected (updatedCount == 0) but got " + updatedCount);
    }
}

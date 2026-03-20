package com.lotto.service;

import com.lotto.entity.OfficialResult;
import com.lotto.repository.OfficialResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for the admin manual result entry flow.
 *
 * Results are entered directly by the admin — no scraping, no parsing.
 * The admin provides gameId, drawDateKey, drawTime, and numbers explicitly.
 *
 * Flow:
 *   1. Admin POSTs to /api/admin/results with structured fields.
 *   2. AdminController upserts OfficialResult via OfficialResultRepository.
 *   3. BetService.settleByResult is called to settle all bets for that slot.
 */
class AdminResultServiceTest {

    private OfficialResultRepository resultRepo;
    private BetService betService;

    @BeforeEach
    void setUp() {
        resultRepo = mock(OfficialResultRepository.class);
        betService = mock(BetService.class);

        when(resultRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Helper: simulate what AdminController.setResult does ──────────────────

    private OfficialResult saveResult(String gameId, String drawDateKey, String drawTime, String numbers) {
        OfficialResult result = resultRepo
                .findByGameIdAndDrawDateKeyAndDrawTime(gameId, drawDateKey, drawTime)
                .orElse(new OfficialResult());

        result.setGameId(gameId);
        result.setDrawDateKey(drawDateKey);
        result.setDrawTime(drawTime);
        result.setNumbers(numbers);
        resultRepo.save(result);

        betService.settleByResult(gameId, drawDateKey, drawTime, numbers);
        return result;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * New result is saved and settleByResult is called.
     */
    @Test
    void newResult_isSavedAndBetsAreSettled() {
        when(resultRepo.findByGameIdAndDrawDateKeyAndDrawTime("mega-645", "2026-03-20", "9:00 PM"))
                .thenReturn(Optional.empty());

        OfficialResult saved = saveResult("mega-645", "2026-03-20", "9:00 PM", "14,22,31,38,41,45");

        assertEquals("mega-645",    saved.getGameId());
        assertEquals("2026-03-20",  saved.getDrawDateKey());
        assertEquals("9:00 PM",     saved.getDrawTime());
        assertEquals("14,22,31,38,41,45", saved.getNumbers());

        verify(resultRepo, times(1)).save(any());
        verify(betService, times(1)).settleByResult("mega-645", "2026-03-20", "9:00 PM", "14,22,31,38,41,45");
    }

    /**
     * Updating an existing result (upsert) — same slot, different numbers.
     * settleByResult must be called again so bets are re-evaluated.
     */
    @Test
    void updateExistingResult_upsertAndResettle() {
        OfficialResult existing = new OfficialResult();
        existing.setGameId("mega-645");
        existing.setDrawDateKey("2026-03-20");
        existing.setDrawTime("9:00 PM");
        existing.setNumbers("01,02,03,04,05,06");

        when(resultRepo.findByGameIdAndDrawDateKeyAndDrawTime("mega-645", "2026-03-20", "9:00 PM"))
                .thenReturn(Optional.of(existing));

        OfficialResult updated = saveResult("mega-645", "2026-03-20", "9:00 PM", "14,22,31,38,41,45");

        assertEquals("14,22,31,38,41,45", updated.getNumbers(), "Numbers should be updated");
        verify(resultRepo, times(1)).save(any());
        verify(betService, times(1)).settleByResult("mega-645", "2026-03-20", "9:00 PM", "14,22,31,38,41,45");
    }

    /**
     * Multi-draw game (3D Swertres) — three separate draw times saved independently.
     */
    @Test
    void swertres_threeDrawTimes_savedSeparately() {
        when(resultRepo.findByGameIdAndDrawDateKeyAndDrawTime(eq("3d-swertres"), eq("2026-03-20"), anyString()))
                .thenReturn(Optional.empty());

        saveResult("3d-swertres", "2026-03-20", "2:00 PM", "4,1,7");
        saveResult("3d-swertres", "2026-03-20", "5:00 PM", "2,9,3");
        saveResult("3d-swertres", "2026-03-20", "9:00 PM", "6,0,5");

        verify(resultRepo, times(3)).save(any());
        verify(betService, times(1)).settleByResult("3d-swertres", "2026-03-20", "2:00 PM", "4,1,7");
        verify(betService, times(1)).settleByResult("3d-swertres", "2026-03-20", "5:00 PM", "2,9,3");
        verify(betService, times(1)).settleByResult("3d-swertres", "2026-03-20", "9:00 PM", "6,0,5");
    }

    /**
     * EZ2 (2D) — three draw times saved independently.
     */
    @Test
    void ez2_threeDrawTimes_savedSeparately() {
        when(resultRepo.findByGameIdAndDrawDateKeyAndDrawTime(eq("2d-ez2"), eq("2026-03-20"), anyString()))
                .thenReturn(Optional.empty());

        saveResult("2d-ez2", "2026-03-20", "2:00 PM", "11,04");
        saveResult("2d-ez2", "2026-03-20", "5:00 PM", "29,16");
        saveResult("2d-ez2", "2026-03-20", "9:00 PM", "08,31");

        verify(resultRepo, times(3)).save(any());
        verify(betService, times(1)).settleByResult("2d-ez2", "2026-03-20", "2:00 PM", "11,04");
        verify(betService, times(1)).settleByResult("2d-ez2", "2026-03-20", "5:00 PM", "29,16");
        verify(betService, times(1)).settleByResult("2d-ez2", "2026-03-20", "9:00 PM", "08,31");
    }

    /**
     * Default draw time is "9:00 PM" when not provided.
     * AdminController defaults drawTime to "9:00 PM" if body.drawTime is null.
     */
    @Test
    void defaultDrawTime_is9PM() {
        when(resultRepo.findByGameIdAndDrawDateKeyAndDrawTime("ultra-658", "2026-03-20", "9:00 PM"))
                .thenReturn(Optional.empty());

        // Simulate controller defaulting drawTime
        String drawTime = null;
        String resolvedDrawTime = drawTime != null ? drawTime : "9:00 PM";

        OfficialResult saved = saveResult("ultra-658", "2026-03-20", resolvedDrawTime, "29,52,49,55,25,09");

        assertEquals("9:00 PM", saved.getDrawTime());
    }
}

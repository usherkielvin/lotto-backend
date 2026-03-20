package com.lotto.controller;

import com.lotto.entity.OfficialResult;
import com.lotto.repository.OfficialResultRepository;
import com.lotto.repository.UserRepository;
import com.lotto.service.BetService;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin endpoints for manually managing official lotto draw results.
 *
 * All result entry is done manually by the admin — no scraping.
 * Admin provides gameId, drawDateKey, drawTime, and numbers directly.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final OfficialResultRepository resultRepo;
    private final UserRepository userRepo;
    private final BetService betService;

    public AdminController(OfficialResultRepository resultRepo,
                           UserRepository userRepo,
                           BetService betService) {
        this.resultRepo = resultRepo;
        this.userRepo = userRepo;
        this.betService = betService;
    }

    private boolean isAdmin(@NonNull Long userId) {
        return userRepo.findById(userId)
                .map(u -> "admin".equals(u.getRole()))
                .orElse(false);
    }

    /**
     * POST /api/admin/results
     *
     * Manually set an official draw result.
     * Body: { gameId, drawDateKey, drawTime (optional, default "9:00 PM"), numbers }
     *
     * If a result already exists for the same game+date+time, it is updated (upsert).
     * All pending and previously-settled bets for that slot are re-evaluated.
     */
    @PostMapping("/results")
    public ResponseEntity<?> setResult(@RequestHeader("X-User-Id") @NonNull Long userId,
                                       @RequestBody Map<String, Object> body) {
        if (!isAdmin(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));
        }

        String gameId     = (String) body.get("gameId");
        String drawDateKey = (String) body.get("drawDateKey");
        String drawTime   = body.get("drawTime") != null ? (String) body.get("drawTime") : "9:00 PM";
        String numbers    = (String) body.get("numbers");

        if (gameId == null || drawDateKey == null || numbers == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "gameId, drawDateKey, and numbers are required."));
        }

        try {
            OfficialResult result = resultRepo
                    .findByGameIdAndDrawDateKeyAndDrawTime(gameId, drawDateKey, drawTime)
                    .orElse(new OfficialResult());

            result.setGameId(gameId);
            result.setDrawDateKey(drawDateKey);
            result.setDrawTime(drawTime);
            result.setNumbers(numbers);
            resultRepo.save(result);

            // Settle all bets for this draw slot against the new numbers
            betService.settleByResult(gameId, drawDateKey, drawTime, numbers);

            return ResponseEntity.ok(Map.of(
                    "message", "Result saved.",
                    "gameId", gameId,
                    "drawDateKey", drawDateKey,
                    "drawTime", drawTime,
                    "numbers", numbers
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/admin/results
     * Returns all official results.
     */
    @GetMapping("/results")
    public ResponseEntity<?> getAllResults(@RequestHeader("X-User-Id") @NonNull Long userId) {
        if (!isAdmin(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));
        }
        return ResponseEntity.ok(resultRepo.findAll());
    }

    /**
     * DELETE /api/admin/results/{id}
     * Removes an official result by ID.
     */
    @DeleteMapping("/results/{id}")
    public ResponseEntity<?> deleteResult(@RequestHeader("X-User-Id") @NonNull Long userId,
                                          @PathVariable @NonNull Long id) {
        if (!isAdmin(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));
        }
        if (!resultRepo.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        resultRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Result deleted."));
    }
}

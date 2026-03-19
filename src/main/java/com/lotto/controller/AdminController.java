package com.lotto.controller;

import com.lotto.entity.OfficialResult;
import com.lotto.repository.OfficialResultRepository;
import com.lotto.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final OfficialResultRepository resultRepo;
    private final UserRepository userRepo;

    public AdminController(OfficialResultRepository resultRepo, UserRepository userRepo) {
        this.resultRepo = resultRepo;
        this.userRepo = userRepo;
    }

    private boolean isAdmin(@NonNull Long userId) {
        return userRepo.findById(userId)
                .map(u -> "admin".equals(u.getRole()))
                .orElse(false);
    }

    @PostMapping("/results")
    public ResponseEntity<?> addOfficialResult(@RequestHeader("X-User-Id") @NonNull Long userId,
                                                 @RequestBody Map<String, Object> body) {
        if (!isAdmin(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));
        }

        try {
            String gameId = (String) body.get("gameId");
            String drawDateKey = (String) body.get("drawDateKey");
            String numbers = (String) body.get("numbers");

            if (gameId == null || drawDateKey == null || numbers == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "gameId, drawDateKey, and numbers are required."));
            }

            OfficialResult result = resultRepo.findByGameIdAndDrawDateKey(gameId, drawDateKey)
                    .orElse(new OfficialResult());
            result.setGameId(gameId);
            result.setDrawDateKey(drawDateKey);
            result.setNumbers(numbers);
            resultRepo.save(result);

            return ResponseEntity.ok(Map.of("message", "Official result saved.", "id", result.getId()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/results")
    public ResponseEntity<?> getAllResults(@RequestHeader("X-User-Id") @NonNull Long userId) {
        if (!isAdmin(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));
        }
        return ResponseEntity.ok(resultRepo.findAll());
    }

    @DeleteMapping("/results/{id}")
    public ResponseEntity<?> deleteResult(@RequestHeader("X-User-Id") @NonNull Long userId,
                                           @PathVariable @NonNull Long id) {
        if (!isAdmin(userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));
        }
        resultRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Result deleted."));
    }
}

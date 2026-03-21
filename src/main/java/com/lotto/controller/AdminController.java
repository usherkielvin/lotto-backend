package com.lotto.controller;

import com.lotto.entity.OfficialResult;
import com.lotto.repository.BetRepository;
import com.lotto.repository.OfficialResultRepository;
import com.lotto.repository.UserRepository;
import com.lotto.service.BetService;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final OfficialResultRepository resultRepo;
    private final UserRepository userRepo;
    private final BetService betService;
    private final BetRepository betRepo;

    public AdminController(OfficialResultRepository resultRepo,
                           UserRepository userRepo,
                           BetService betService,
                           BetRepository betRepo) {
        this.resultRepo = resultRepo;
        this.userRepo = userRepo;
        this.betService = betService;
        this.betRepo = betRepo;
    }

    private boolean isAdmin(@NonNull Long userId) {
        return userRepo.findById(userId)
                .map(u -> "admin".equals(u.getRole()))
                .orElse(false);
    }

    // ── POST /api/admin/results — single upsert ───────────────────────────────
    @PostMapping("/results")
    public ResponseEntity<?> setResult(@RequestHeader("X-User-Id") @NonNull Long userId,
                                       @RequestBody Map<String, Object> body) {
        if (!isAdmin(userId)) return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));

        String gameId      = (String) body.get("gameId");
        String drawDateKey = (String) body.get("drawDateKey");
        String drawTime    = body.get("drawTime") != null ? (String) body.get("drawTime") : "9:00 PM";
        String numbers     = (String) body.get("numbers");

        if (gameId == null || drawDateKey == null || numbers == null)
            return ResponseEntity.badRequest().body(Map.of("error", "gameId, drawDateKey, and numbers are required."));

        try {
            OfficialResult result = resultRepo
                    .findByGameIdAndDrawDateKeyAndDrawTime(gameId, drawDateKey, drawTime)
                    .orElse(new OfficialResult());
            result.setGameId(gameId);
            result.setDrawDateKey(drawDateKey);
            result.setDrawTime(drawTime);
            result.setNumbers(numbers);

            // Persist jackpot if provided
            Object jackpotRaw = body.get("jackpot");
            if (jackpotRaw instanceof Number) {
                result.setJackpot(((Number) jackpotRaw).longValue());
            } else if (jackpotRaw instanceof String s && !s.isBlank()) {
                try { result.setJackpot(Long.parseLong(s.replaceAll("[^0-9]", ""))); }
                catch (NumberFormatException ignored) {}
            }

            // Persist official winner count if provided
            Object winnersRaw = body.get("winners");
            if (winnersRaw instanceof Number) {
                result.setWinners(((Number) winnersRaw).intValue());
            } else if (winnersRaw instanceof String ws && !ws.isBlank()) {
                try { result.setWinners(Integer.parseInt(ws.replaceAll("[^0-9]", ""))); }
                catch (NumberFormatException ignored) {}
            }

            resultRepo.save(result);
            betService.settleByResult(gameId, drawDateKey, drawTime, numbers);
            return ResponseEntity.ok(Map.of("message", "Result saved."));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── GET /api/admin/results ────────────────────────────────────────────────
    @GetMapping("/results")
    public ResponseEntity<?> getAllResults(@RequestHeader("X-User-Id") @NonNull Long userId) {
        if (!isAdmin(userId)) return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));
        List<Map<String, Object>> out = new ArrayList<>();
        for (OfficialResult r : resultRepo.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id",          r.getId());
            row.put("gameId",      r.getGameId());
            row.put("drawDateKey", r.getDrawDateKey());
            row.put("drawTime",    r.getDrawTime());
            row.put("numbers",     r.getNumbers());
            row.put("winners",     r.getWinners() != null ? r.getWinners() : 0);
            if (r.getJackpot() != null) row.put("jackpot", r.getJackpot());
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    // ── DELETE /api/admin/results/{id} ────────────────────────────────────────
    @DeleteMapping("/results/{id}")
    public ResponseEntity<?> deleteResult(@RequestHeader("X-User-Id") @NonNull Long userId,
                                          @PathVariable @NonNull Long id) {
        if (!isAdmin(userId)) return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));
        if (!resultRepo.existsById(id)) return ResponseEntity.notFound().build();
        resultRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Result deleted."));
    }

    // ── POST /api/admin/import ────────────────────────────────────────────────
    //
    // Accepts raw copy-pasted text from PCSO results pages and bulk-upserts.
    //
    // Handles two real-world formats:
    //
    //  Table format (pcso.gov.ph, lottoresults sites):
    //    Grand Lotto 6/55  02-18-47-12-32-11  3/21/2026  45,000,000.00  0
    //    3D Lotto 2PM      2-8-9              3/21/2026  4,500.00       170
    //
    //  GMA multi-line format:
    //    Grand Lotto 6/55:
    //    49 08 12 29 22 18
    //    P29,700,000.00
    //
    //    Swertres 9PM:
    //    5 3 6
    //
    // Returns { imported, skipped, errors[] }
    // ─────────────────────────────────────────────────────────────────────────
    @PostMapping("/import")
    public ResponseEntity<?> importResults(@RequestHeader("X-User-Id") @NonNull Long userId,
                                           @RequestBody Map<String, Object> body) {
        if (!isAdmin(userId)) return ResponseEntity.status(403).body(Map.of("error", "Admin access required."));

        String text = (String) body.get("text");
        if (text == null || text.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "text is required."));

        List<ParsedResult> parsed = parseText(text);
        int imported = 0, skipped = 0;
        long totalWinners = 0;
        List<String> errors = new ArrayList<>();

        for (ParsedResult pr : parsed) {
            try {
                OfficialResult result = resultRepo
                        .findByGameIdAndDrawDateKeyAndDrawTime(pr.gameId, pr.drawDateKey, pr.drawTime)
                        .orElse(new OfficialResult());
                result.setGameId(pr.gameId);
                result.setDrawDateKey(pr.drawDateKey);
                result.setDrawTime(pr.drawTime);
                result.setNumbers(pr.numbers);
                if (pr.jackpot != null) result.setJackpot(pr.jackpot);
                if (pr.winners != null) result.setWinners(pr.winners);
                resultRepo.save(result);
                betService.settleByResult(pr.gameId, pr.drawDateKey, pr.drawTime, pr.numbers);
                totalWinners += betRepo.countByGameIdAndDrawDateKeyAndDrawTimeAndStatus(
                    pr.gameId, pr.drawDateKey, pr.drawTime, "won");
                imported++;
            } catch (Exception e) {
                skipped++;
                errors.add(pr.gameId + " " + pr.drawDateKey + ": " + e.getMessage());
            }
        }

        return ResponseEntity.ok(Map.of("imported", imported, "skipped", skipped, "winners", totalWinners, "errors", errors));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Parser
    // ═════════════════════════════════════════════════════════════════════════

    private static class ParsedResult {
        String gameId, drawDateKey, drawTime, numbers;
        Long jackpot;
        Integer winners;
        ParsedResult(String g, String d, String t, String n, Long j, Integer w) {
            gameId = g; drawDateKey = d; drawTime = t; numbers = n; jackpot = j; winners = w;
        }
    }

    /**
     * Game patterns ordered most-specific first.
     * IDs match exactly what's seeded in lotto_games table.
     */
    private static final List<Map.Entry<Pattern, String>> GAME_PATTERNS = List.of(
        gp("ultra\\s+lotto\\s+6/58|ultra\\s+lotto|\\b6/58\\b",  "ultra-658"),
        gp("grand\\s+lotto\\s+6/55|grand\\s+lotto|\\b6/55\\b",  "grand-655"),
        gp("super\\s+lotto\\s+6/49|super\\s+lotto|\\b6/49\\b",  "super-649"),
        gp("mega\\s+lotto\\s+6/45|mega\\s+lotto|\\b6/45\\b",    "mega-645"),
        gp("lotto\\s+6/42|\\b6/42\\b",                          "lotto-642"),
        gp("6[- ]digit|6d\\s+lotto|\\b6d\\b",                   "6digit"),
        gp("4[- ]digit|4d\\s+lotto|\\b4d\\b",                   "4digit"),
        gp("swertres|3d\\s+lotto|\\b3d\\b",                     "3d-swertres"),
        gp("ez2|2d\\s+lotto|\\b2d\\b",                          "2d-ez2")
    );

    private static Map.Entry<Pattern, String> gp(String regex, String id) {
        return Map.entry(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), id);
    }

    // Time: "9PM" "9:00 PM" "2:00PM" "5PM" "2PM"
    private static final Pattern TIME_PAT = Pattern.compile(
        "\\b(\\d{1,2}(?::\\d{2})?\\s*(?:AM|PM))\\b", Pattern.CASE_INSENSITIVE
    );

    // Date: M/D/YYYY or MM/DD/YYYY (slash only, 4-digit year) OR YYYY-MM-DD
    // Slash-only prevents matching lotto numbers like "02-18-47" as a date
    private static final Pattern DATE_PAT = Pattern.compile(
        "\\b(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}/\\d{4})\\b"
    );

    // Jackpot: "P29,700,000.00" "₱45,000,000" "45,000,000.00"
    private static final Pattern JACKPOT_PAT = Pattern.compile(
        "[P₱]\\s*([\\d,]+(?:\\.\\d{1,2})?)"
    );
    // Bare comma-grouped number (no currency prefix) — only used as fallback
    private static final Pattern BARE_AMOUNT_PAT = Pattern.compile(
        "\\b(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?)\\b"
    );

    // Winner count: trailing plain integer at end of table line, e.g. "... 4,500.00   170"
    // Must be a small-ish integer (≤ 999999) not preceded by a currency symbol
    private static final Pattern WINNER_COUNT_PAT = Pattern.compile(
        "(?<![P₱\\d,\\.])\\b(\\d{1,6})\\s*$"
    );

    // Space-separated whole-line numbers: "08 32 20 36 05 12" / "5 3 6" / "08 09"
    private static final Pattern SPACE_NUMS_PAT = Pattern.compile(
        "^(\\d{1,2}(?:\\s+\\d{1,2}){1,5})$"
    );

    // Dash-separated numbers — require ≥2 dashes so "3/21" or "3-21" won't match
    private static final Pattern DASH_NUMS_PAT = Pattern.compile(
        "\\b(\\d{1,2}(?:-\\d{1,2}){2,5})\\b"
    );

    private List<ParsedResult> parseText(String text) {
        String[] lines = text.split("\\r?\\n");
        List<ParsedResult> results = new ArrayList<>();

        // Context date: first valid date found anywhere in the pasted block
        String contextDateKey = findFirstDate(text);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isBlank()) continue;

            String gameId = detectGame(line);
            if (gameId == null) continue;

            // Time embedded in label: "3D Lotto 2PM", "Swertres 9PM", "2D 5PM"
            String drawTime = extractTime(line);

            // Date on same line (table format) or fall back to context date
            String drawDateKey = extractDate(line);
            if (drawDateKey == null) drawDateKey = contextDateKey;
            if (drawDateKey == null) continue; // no date anywhere — skip

            String numbers = null;
            Long jackpot   = null;
            Integer winners = null;

            // ── Try inline dash-separated numbers (table format) ──────────────
            Matcher dm = DASH_NUMS_PAT.matcher(line);
            if (dm.find()) {
                numbers = dm.group(1).replace("-", ",");
            }

            // Jackpot on same line
            jackpot = extractJackpot(line);

            // Winner count: trailing plain integer after jackpot in table format
            // e.g. "3D Lotto 2PM  2-8-9  3/21/2026  4,500.00  170"
            if (jackpot != null) {
                String stripped = line.replaceAll("[P₱]\\s*[\\d,]+(?:\\.\\d{1,2})?", "")
                                      .replaceAll("\\b\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?", "").trim();
                Matcher wm = WINNER_COUNT_PAT.matcher(stripped);
                if (wm.find()) {
                    try { winners = Integer.parseInt(wm.group(1)); } catch (NumberFormatException ignored) {}
                }
            }

            // ── If no inline numbers, look ahead (GMA multi-line format) ──────
            if (numbers == null) {
                for (int j = i + 1; j < lines.length && j <= i + 4; j++) {
                    String next = lines[j].trim();
                    if (next.isBlank()) continue;
                    if (detectGame(next) != null) break; // hit next label

                    Matcher sm = SPACE_NUMS_PAT.matcher(next);
                    if (sm.matches()) {
                        numbers = sm.group(1).trim().replaceAll("\\s+", ",");
                        // Jackpot is usually the line right after the numbers on GMA
                        if (jackpot == null && j + 1 < lines.length) {
                            String after = lines[j + 1].trim();
                            if (!after.isBlank() && detectGame(after) == null) {
                                jackpot = extractJackpot(after);
                            }
                        }
                        break;
                    }
                    Matcher ndm = DASH_NUMS_PAT.matcher(next);
                    if (ndm.find()) {
                        numbers = ndm.group(1).replace("-", ",");
                        break;
                    }
                }
            }

            if (numbers != null) {
                results.add(new ParsedResult(gameId, drawDateKey, normalizeTime(drawTime), numbers, jackpot, winners));
            }
        }

        return results;
    }

    private String findFirstDate(String text) {
        Matcher m = DATE_PAT.matcher(text);
        while (m.find()) {
            String d = toDateKey(m.group(1));
            if (d != null) return d;
        }
        return null;
    }

    private String extractDate(String line) {
        Matcher m = DATE_PAT.matcher(line);
        if (m.find()) return toDateKey(m.group(1));
        return null;
    }

    private String extractTime(String line) {
        Matcher m = TIME_PAT.matcher(line);
        if (m.find()) return m.group(1).trim();
        return "9:00 PM";
    }

    /**
     * Extract jackpot from "P29,700,000.00", "₱45,000,000", or bare "45,000,000.00".
     * Returns null if nothing plausible found.
     */
    private Long extractJackpot(String line) {
        // Currency-prefixed first
        Matcher m = JACKPOT_PAT.matcher(line);
        if (m.find()) {
            return parseLongAmount(m.group(1));
        }
        // Bare comma-grouped number (e.g. table column "45,000,000.00")
        Matcher bm = BARE_AMOUNT_PAT.matcher(line);
        while (bm.find()) {
            Long val = parseLongAmount(bm.group(1));
            if (val != null && val >= 4000) return val; // min plausible jackpot
        }
        return null;
    }

    private Long parseLongAmount(String s) {
        try {
            String clean = s.replaceAll(",", "");
            int dot = clean.indexOf('.');
            if (dot >= 0) clean = clean.substring(0, dot);
            return Long.parseLong(clean);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String detectGame(String line) {
        String lower = line.toLowerCase();
        for (Map.Entry<Pattern, String> e : GAME_PATTERNS) {
            if (e.getKey().matcher(lower).find()) return e.getValue();
        }
        return null;
    }

    /** "9PM" → "9:00 PM", "2PM" → "2:00 PM", "11:00AM" → "11:00 AM" */
    private String normalizeTime(String raw) {
        if (raw == null) return "9:00 PM";
        raw = raw.trim().toUpperCase().replaceAll("\\s+", " ");
        if (raw.contains(":")) return raw.replaceAll("(\\d)(AM|PM)", "$1 $2");
        return raw.replaceAll("(\\d+)(AM|PM)", "$1:00 $2");
    }

    /** M/D/YYYY or YYYY-MM-DD → YYYY-MM-DD. Returns null if invalid. */
    private String toDateKey(String raw) {
        try {
            // Already YYYY-MM-DD
            if (raw.matches("\\d{4}-\\d{2}-\\d{2}")) return raw;
            // M/D/YYYY
            String[] p = raw.split("/");
            if (p.length != 3) return null;
            int month = Integer.parseInt(p[0]);
            int day   = Integer.parseInt(p[1]);
            int year  = Integer.parseInt(p[2]);
            if (year < 100) year += 2000;
            if (month < 1 || month > 12 || day < 1 || day > 31) return null;
            return String.format("%04d-%02d-%02d", year, month, day);
        } catch (Exception e) {
            return null;
        }
    }
}

package com.lotto.service;

import com.lotto.entity.Balance;
import com.lotto.entity.Bet;
import com.lotto.entity.LottoGame;
import com.lotto.entity.OfficialResult;
import com.lotto.repository.BalanceRepository;
import com.lotto.repository.BetRepository;
import com.lotto.repository.LottoGameRepository;
import com.lotto.repository.OfficialResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.lang.NonNull;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class BetService {

    private static final Logger log = LoggerFactory.getLogger(BetService.class);
    private static final DateTimeFormatter DRAW_TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter PLACED_AT_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.ENGLISH);
    private static final LocalTime DEFAULT_DRAW_TIME = LocalTime.of(21, 0);

    private final BetRepository betRepo;
    private final BalanceRepository balanceRepo;
    private final LottoGameRepository gameRepo;
    private final OfficialResultRepository resultRepo;

    public BetService(BetRepository betRepo, BalanceRepository balanceRepo, LottoGameRepository gameRepo, OfficialResultRepository resultRepo) {
        this.betRepo = betRepo;
        this.balanceRepo = balanceRepo;
        this.gameRepo = gameRepo;
        this.resultRepo = resultRepo;
    }

    @Transactional
    public Map<String, Object> placeBet(@NonNull Long userId, @NonNull String gameId, List<Integer> numbers, BigDecimal stake) {
        // Validate balance
        Balance balance = balanceRepo.findById(userId)
                .orElseThrow(() -> new RuntimeException("Balance not found."));
        if (balance.getAmount().compareTo(stake) < 0) {
            throw new RuntimeException("Insufficient balance.");
        }
        LottoGame game = gameRepo.findById(gameId)
                .orElseThrow(() -> new RuntimeException("Game not found."));

        int requiredCount = getRequiredCount(game);
        if (numbers == null || numbers.size() != requiredCount) {
            throw new RuntimeException("Exactly " + requiredCount + " numbers required for " + game.getName() + ".");
        }

        LocalDateTime now = LocalDateTime.now();
        DrawSlot drawSlot = findNextDrawSlot(game, now);

        Bet bet = new Bet();
        bet.setId(System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6));
        bet.setUserId(userId);
        bet.setGameId(gameId);
        bet.setGameName(game.getName());
        Collections.sort(numbers);
        bet.setNumbers(numbersToString(numbers));
        bet.setStake(stake);
        bet.setDrawDateKey(drawSlot.drawDateKey);
        bet.setDrawTime(drawSlot.drawTimeLabel);
        bet.setPlacedAt(now.format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")));
        bet.setStatus("pending");
        betRepo.save(bet);

        balance.setAmount(balance.getAmount().subtract(stake));
        balanceRepo.save(balance);

        return betToMap(bet, game);
    }

    // ── Fetch & Settle ─────────────────────────────────────────────────────────

    @Transactional
    public List<Map<String, Object>> getActiveBets(@NonNull Long userId) {
        settleIfNeeded(userId);
        List<Bet> bets = betRepo.findByUserIdAndStatusOrderByPlacedAtDesc(userId, "pending");
        return enrichBets(bets);
    }

    @Transactional
    public List<Map<String, Object>> getBetHistory(@NonNull Long userId) {
        settleIfNeeded(userId);
        List<Bet> bets = betRepo.findByUserIdAndStatusNotOrderByPlacedAtDesc(userId, "pending");
        return enrichBets(bets);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getUnclaimedWins(@NonNull Long userId) {
        List<Bet> bets = betRepo.findByUserIdAndStatusAndClaimedFalseOrderByPlacedAtDesc(userId, "won");
        return enrichBets(bets);
    }

    @Transactional
    public void claimBet(@NonNull Long userId, String betId) {
        Bet bet = betRepo.findById(betId)
            .orElseThrow(() -> new RuntimeException("Bet not found."));
        if (!bet.getUserId().equals(userId))
            throw new RuntimeException("Not your bet.");
        if (!"won".equals(bet.getStatus()))
            throw new RuntimeException("Bet is not a winning bet.");
        if (bet.isClaimed())
            throw new RuntimeException("Already claimed.");
        bet.setClaimed(true);
        betRepo.save(bet);
    }

    // ── Settle all pending bets for a specific result (called on import) ────────

    @Transactional
    public void settleByResult(String gameId, String drawDateKey, String drawTime, String officialNumbersCsv) {
        String normalizedTime = formatDrawTime(parseSingleDrawTime(drawTime));

        // Collect ALL bets for this slot — pending and already settled
        // Use a map to deduplicate by bet id
        Map<String, Bet> betMap = new LinkedHashMap<>();

        // Pending bets (case-insensitive time match)
        for (Bet b : betRepo.findPendingByGameDateTimeIgnoreCase(gameId, drawDateKey, normalizedTime))
            betMap.put(b.getId(), b);

        // Also try raw time string if it differs from normalized
        if (!drawTime.equalsIgnoreCase(normalizedTime)) {
            for (Bet b : betRepo.findPendingByGameDateTimeIgnoreCase(gameId, drawDateKey, drawTime))
                betMap.put(b.getId(), b);
        }

        // Already-settled bets for this slot — re-evaluate against latest numbers
        for (Bet b : betRepo.findByGameIdAndDrawDateKeyAndDrawTimeAndStatusIn(
                gameId, drawDateKey, normalizedTime, List.of("won", "lost")))
            betMap.put(b.getId(), b);

        if (betMap.isEmpty()) {
            log.info("No bets to settle for {} {} {}", gameId, drawDateKey, normalizedTime);
            return;
        }

        List<Integer> official = stringToNumbers(officialNumbersCsv);
        Map<Long, BigDecimal> creditByUser  = new LinkedHashMap<>(); // new correct payouts
        Map<Long, BigDecimal> deductByUser  = new LinkedHashMap<>(); // old wrong payouts to reverse

        for (Bet bet : betMap.values()) {
            // Reverse any previously credited payout so we don't double-credit
            BigDecimal oldPayout = bet.getPayout() != null ? bet.getPayout() : BigDecimal.ZERO;
            if (oldPayout.compareTo(BigDecimal.ZERO) > 0
                    && ("won".equals(bet.getStatus()) || "lost".equals(bet.getStatus()))) {
                deductByUser.merge(bet.getUserId(), oldPayout, BigDecimal::add);
            }

            List<Integer> picked = stringToNumbers(bet.getNumbers());
            int matches = countMatches(picked, official);
            BigDecimal payout = computePayoutForGame(gameId, matches, bet.getStake());

            bet.setDrawTime(normalizedTime);
            bet.setStatus(payout.compareTo(BigDecimal.ZERO) > 0 ? "won" : "lost");
            bet.setMatches(matches);
            bet.setPayout(payout);
            bet.setOfficialNumbers(officialNumbersCsv);
            // Reset claimed so user can re-acknowledge if result changed
            if (payout.compareTo(BigDecimal.ZERO) == 0) bet.setClaimed(false);
            betRepo.save(bet);

            if (payout.compareTo(BigDecimal.ZERO) > 0) {
                creditByUser.merge(bet.getUserId(), payout, BigDecimal::add);
            }
        }

        // Apply net balance change per user
        Set<Long> allUsers = new LinkedHashSet<>();
        allUsers.addAll(creditByUser.keySet());
        allUsers.addAll(deductByUser.keySet());

        BigDecimal totalNet = BigDecimal.ZERO;
        for (Long uid : allUsers) {
            Balance balance = balanceRepo.findById(uid).orElse(null);
            if (balance == null) continue;
            BigDecimal credit = creditByUser.getOrDefault(uid, BigDecimal.ZERO);
            BigDecimal deduct = deductByUser.getOrDefault(uid, BigDecimal.ZERO);
            BigDecimal net = credit.subtract(deduct);
            if (net.compareTo(BigDecimal.ZERO) != 0) {
                balance.setAmount(balance.getAmount().add(net));
                balanceRepo.save(balance);
                totalNet = totalNet.add(net);
            }
        }

        log.info("Re-settled {} bets for {} {} {} — net payout delta: {}",
            betMap.size(), gameId, drawDateKey, normalizedTime, totalNet);
    }

    private void settleIfNeeded(@NonNull Long userId) {
        List<Bet> pending = betRepo.findByUserIdAndStatusOrderByPlacedAtDesc(userId, "pending");
        LocalDateTime now = LocalDateTime.now();
        BigDecimal totalPayout = BigDecimal.ZERO;
        boolean anySettled = false;

        for (Bet bet : pending) {
            LottoGame game = gameRepo.findById(bet.getGameId()).orElse(null);
            if (game == null) continue;

            LocalDate drawDate = LocalDate.parse(bet.getDrawDateKey());
            String resolvedDrawTime = resolveDrawTimeLabel(bet, game);
            LocalTime drawClock = parseSingleDrawTime(resolvedDrawTime);
            LocalDateTime drawAt = LocalDateTime.of(drawDate, drawClock);
            if (now.isBefore(drawAt)) continue;

            // Only settle if an official result exists in the DB — never use seeded RNG fallback
            // to avoid settling with wrong numbers before admin imports the real result.
            Optional<OfficialResult> officialResult = findOfficialResult(game.getId(), bet.getDrawDateKey(), resolvedDrawTime);
            if (officialResult.isEmpty()) {
                log.debug("No official result yet for {} {} {} — skipping lazy settle", game.getId(), bet.getDrawDateKey(), resolvedDrawTime);
                continue;
            }

            List<Integer> official = stringToNumbers(officialResult.get().getNumbers());
            List<Integer> picked = stringToNumbers(bet.getNumbers());
            int matches = countMatches(picked, official);
            BigDecimal payout = computePayoutForGame(bet.getGameId(), matches, bet.getStake());

            bet.setDrawTime(resolvedDrawTime);
            bet.setStatus(payout.compareTo(BigDecimal.ZERO) > 0 ? "won" : "lost");
            bet.setMatches(matches);
            bet.setPayout(payout);
            bet.setOfficialNumbers(officialResult.get().getNumbers());
            betRepo.save(bet);

            if (payout.compareTo(BigDecimal.ZERO) > 0) {
                totalPayout = totalPayout.add(payout);
            }
            anySettled = true;
        }

        if (anySettled && totalPayout.compareTo(BigDecimal.ZERO) > 0) {
            Balance balance = balanceRepo.findById(userId).orElse(null);
            if (balance != null) {
                balance.setAmount(balance.getAmount().add(totalPayout));
                balanceRepo.save(balance);
            }
        }
    }

    /**
     * Looks up an official result tolerating minor draw-time format differences
     * (e.g. "9:00 PM" vs "9:00 pm"). Falls back to a case-insensitive JPQL query.
     */
    private Optional<OfficialResult> findOfficialResult(String gameId, String drawDateKey, String drawTime) {
        // Exact match first
        Optional<OfficialResult> exact = resultRepo.findByGameIdAndDrawDateKeyAndDrawTime(gameId, drawDateKey, drawTime);
        if (exact.isPresent()) return exact;

        // Try normalized uppercase
        String upper = drawTime.toUpperCase(Locale.ENGLISH);
        if (!upper.equals(drawTime)) {
            Optional<OfficialResult> up = resultRepo.findByGameIdAndDrawDateKeyAndDrawTime(gameId, drawDateKey, upper);
            if (up.isPresent()) return up;
        }

        // Try lowercase
        String lower = drawTime.toLowerCase(Locale.ENGLISH);
        if (!lower.equals(drawTime)) {
            Optional<OfficialResult> lo = resultRepo.findByGameIdAndDrawDateKeyAndDrawTime(gameId, drawDateKey, lower);
            if (lo.isPresent()) return lo;
        }

        return Optional.empty();
    }

    // ── Get required number count for a game ─────────────────────────────────

    private int getRequiredCount(LottoGame game) {
        String id = game.getId().toLowerCase();
        String name = game.getName().toLowerCase();
        if (id.contains("2d") || name.contains("ez2") || name.contains("2d")) return 2;
        if (id.contains("3d") || name.contains("swertres") || name.contains("3d") || game.getMaxNumber() == 999) return 3;
        if (id.contains("4d") || name.contains("4-digit") || name.contains("4d") || game.getMaxNumber() == 9999) return 4;
        if (id.contains("6digit") || id.contains("6-digit") || (game.getMaxNumber() == 9 && name.contains("digit"))) return 6;
        return 6; // all standard 6-number lotto games
    }



    private int countMatches(List<Integer> picked, List<Integer> official) {
        // For digit games (3D/4D/6D/2D), official list may have same size as picked
        // Use set intersection for 6-number games, positional for digit games
        if (picked.size() == official.size() && picked.size() <= 4) {
            // Digit game: count exact positional matches
            int matches = 0;
            for (int i = 0; i < picked.size(); i++) {
                if (picked.get(i).equals(official.get(i))) matches++;
            }
            return matches;
        }
        Set<Integer> officialSet = new HashSet<>(official);
        return (int) picked.stream().filter(officialSet::contains).count();
    }

    /** Game-aware payout: 2D/3D/4D use exact-match rules, 6-number games use match-count rules. */
    private BigDecimal computePayoutForGame(String gameId, int matches, BigDecimal stake) {
        String id = gameId.toLowerCase();
        if (id.contains("2d") || id.contains("ez2")) {
            // 2D: both digits must match exactly → 2 matches = win
            return matches == 2 ? stake.multiply(new BigDecimal("4000")) : BigDecimal.ZERO;
        }
        if (id.contains("3d") || id.contains("swertres")) {
            return matches == 3 ? stake.multiply(new BigDecimal("450")) : BigDecimal.ZERO;
        }
        if (id.contains("4d") || id.contains("4digit")) {
            return matches == 4 ? stake.multiply(new BigDecimal("10000")) : BigDecimal.ZERO;
        }
        // 6-number lotto games
        switch (matches) {
            case 6: return stake.multiply(new BigDecimal("50000"));
            case 5: return stake.multiply(new BigDecimal("5000"));
            case 4: return stake.multiply(new BigDecimal("500"));
            case 3: return stake.multiply(new BigDecimal("50"));
            default: return BigDecimal.ZERO;
        }
    }

    // ── Utils ──────────────────────────────────────────────────────────────────

    private DrawSlot findNextDrawSlot(LottoGame game, LocalDateTime now) {
        List<LocalTime> drawTimes = parseDrawTimes(game.getDrawTime());
        Set<Integer> drawDays = parseDrawDays(game.getDrawDays());

        for (int dayOffset = 0; dayOffset <= 14; dayOffset++) {
            LocalDate date = now.toLocalDate().plusDays(dayOffset);
            int day = date.getDayOfWeek().getValue(); // 1=Mon ... 7=Sun
            if (!drawDays.contains(day)) continue;

            for (LocalTime time : drawTimes) {
                LocalDateTime candidate = LocalDateTime.of(date, time);
                if (candidate.isAfter(now)) {
                    return new DrawSlot(date.toString(), formatDrawTime(time));
                }
            }
        }

        // Fallback should be unreachable, but preserve a safe default draw slot.
        LocalDate fallbackDate = now.toLocalDate().plusDays(1);
        return new DrawSlot(fallbackDate.toString(), formatDrawTime(drawTimes.get(0)));
    }

    private Set<Integer> parseDrawDays(String drawDaysCsv) {
        Set<Integer> days = new LinkedHashSet<>();
        if (drawDaysCsv != null) {
            for (String part : drawDaysCsv.split(",")) {
                String token = part.trim();
                if (token.isEmpty()) continue;
                try {
                    int day = Integer.parseInt(token);
                    if (day >= 1 && day <= 7) {
                        days.add(day);
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore malformed day entries and fall back to defaults below.
                }
            }
        }
        if (days.isEmpty()) {
            days.addAll(Arrays.asList(1, 2, 3, 4, 5, 6, 7));
        }
        return days;
    }

    private List<LocalTime> parseDrawTimes(String drawTimeCsv) {
        List<LocalTime> times = new ArrayList<>();
        if (drawTimeCsv != null) {
            for (String part : drawTimeCsv.split(",")) {
                String token = part.trim();
                if (token.isEmpty()) continue;
                times.add(parseSingleDrawTime(token));
            }
        }
        if (times.isEmpty()) {
            times.add(DEFAULT_DRAW_TIME);
        }
        Collections.sort(times);
        return times;
    }

    private LocalTime parseSingleDrawTime(String drawTime) {
        if (drawTime == null || drawTime.isBlank()) return DEFAULT_DRAW_TIME;
        try {
            return LocalTime.parse(drawTime.trim().toUpperCase(Locale.ENGLISH), DRAW_TIME_FORMATTER);
        } catch (RuntimeException ignored) {
            return DEFAULT_DRAW_TIME;
        }
    }

    private String formatDrawTime(LocalTime time) {
        return time.format(DRAW_TIME_FORMATTER).toUpperCase(Locale.ENGLISH);
    }

    private String resolveDrawTimeLabel(Bet bet, LottoGame game) {
        if (bet.getDrawTime() != null && !bet.getDrawTime().isBlank()) {
            return formatDrawTime(parseSingleDrawTime(bet.getDrawTime()));
        }

        List<LocalTime> drawTimes = parseDrawTimes(game != null ? game.getDrawTime() : null);
        LocalDate drawDate;
        try {
            drawDate = LocalDate.parse(bet.getDrawDateKey());
        } catch (RuntimeException ignored) {
            return formatDrawTime(drawTimes.get(drawTimes.size() - 1));
        }

        if (bet.getPlacedAt() != null && !bet.getPlacedAt().isBlank()) {
            try {
                LocalDateTime placedAt = LocalDateTime.parse(bet.getPlacedAt(), PLACED_AT_FORMATTER);
                for (LocalTime time : drawTimes) {
                    LocalDateTime drawAt = LocalDateTime.of(drawDate, time);
                    if (drawAt.isAfter(placedAt)) {
                        return formatDrawTime(time);
                    }
                }
            } catch (RuntimeException ignored) {
                // Fall through to latest draw slot for the date.
            }
        }

        return formatDrawTime(drawTimes.get(drawTimes.size() - 1));
    }

    private String numbersToString(List<Integer> nums) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nums.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(nums.get(i));
        }
        return sb.toString();
    }

    private List<Integer> stringToNumbers(String s) {
        List<Integer> list = new ArrayList<>();
        for (String part : s.split(",")) {
            list.add(Integer.parseInt(part.trim()));
        }
        return list;
    }

    private List<Map<String, Object>> enrichBets(List<Bet> bets) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Bet bet : bets) {
            LottoGame game = gameRepo.findById(bet.getGameId()).orElse(null);
            result.add(betToMap(bet, game));
        }
        return result;
    }

    private Map<String, Object> betToMap(Bet bet, LottoGame game) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", bet.getId());
        m.put("gameId", bet.getGameId());
        m.put("gameName", bet.getGameName());
        m.put("numbers", stringToNumbers(bet.getNumbers()));
        m.put("stake", bet.getStake());
        m.put("drawDateKey", bet.getDrawDateKey());
        m.put("drawTime", resolveDrawTimeLabel(bet, game));
        m.put("placedAt", bet.getPlacedAt());
        m.put("status", bet.getStatus());
        m.put("matches", bet.getMatches());
        m.put("payout", bet.getPayout());
        m.put("officialNumbers", bet.getOfficialNumbers() != null ? stringToNumbers(bet.getOfficialNumbers()) : null);
        m.put("jackpot", game != null ? game.getJackpot() : null);
        m.put("claimed", bet.isClaimed());
        return m;
    }

    private static class DrawSlot {
        private final String drawDateKey;
        private final String drawTimeLabel;

        private DrawSlot(String drawDateKey, String drawTimeLabel) {
            this.drawDateKey = drawDateKey;
            this.drawTimeLabel = drawTimeLabel;
        }
    }
}

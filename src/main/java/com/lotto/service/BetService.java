package com.lotto.service;

import com.lotto.entity.Balance;
import com.lotto.entity.Bet;
import com.lotto.entity.LottoGame;
import com.lotto.repository.BalanceRepository;
import com.lotto.repository.BetRepository;
import com.lotto.repository.LottoGameRepository;
import com.lotto.repository.OfficialResultRepository;
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

    private static final DateTimeFormatter DRAW_TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
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

    // ── Place Bet ──────────────────────────────────────────────────────────────

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

    // ── Settle pending bets past scheduled draw time ───────────────────────────

    private void settleIfNeeded(@NonNull Long userId) {
        List<Bet> pending = betRepo.findByUserIdAndStatusOrderByPlacedAtDesc(userId, "pending");
        LocalDateTime now = LocalDateTime.now();
        BigDecimal totalPayout = BigDecimal.ZERO;
        boolean anySettled = false;

        for (Bet bet : pending) {
            LocalDate drawDate = LocalDate.parse(bet.getDrawDateKey());
            LocalTime drawClock = parseSingleDrawTime(bet.getDrawTime());
            LocalDateTime drawAt = LocalDateTime.of(drawDate, drawClock);
            if (now.isBefore(drawAt)) continue;

            LottoGame game = gameRepo.findById(bet.getGameId()).orElse(null);
            if (game == null) continue;

            List<Integer> official = getOfficialNumbers(game, bet.getDrawDateKey());
            List<Integer> picked = stringToNumbers(bet.getNumbers());
            int matches = countMatches(picked, official);
            BigDecimal payout = computePayout(matches, bet.getStake());

            bet.setStatus(payout.compareTo(BigDecimal.ZERO) > 0 ? "won" : "lost");
            bet.setMatches(matches);
            bet.setPayout(payout);
            bet.setOfficialNumbers(numbersToString(official));
            betRepo.save(bet);

            totalPayout = totalPayout.add(payout);
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

    // ── Get official numbers (from DB or seeded fallback) ────────────────────

    private List<Integer> getOfficialNumbers(LottoGame game, String drawDateKey) {
        return resultRepo.findByGameIdAndDrawDateKey(game.getId(), drawDateKey)
                .map(result -> stringToNumbers(result.getNumbers()))
                .orElseGet(() -> buildOfficialNumbers(game, drawDateKey));
    }

    // ── Seeded RNG (same logic as frontend) ───────────────────────────────────

    private List<Integer> buildOfficialNumbers(LottoGame game, String drawDateKey) {
        String seed = "pcso:" + game.getId() + ":" + drawDateKey;
        long hash = 2166136261L;
        for (char c : seed.toCharArray()) {
            hash ^= c;
            hash = (hash * 16777619L) & 0xFFFFFFFFL;
        }

        int required = getRequiredCount(game);
        String id = game.getId().toLowerCase();
        String name = game.getName().toLowerCase();
        boolean isDigitGame = id.contains("3d") || name.contains("swertres") || name.contains("3d")
                || id.contains("4d") || name.contains("4-digit")
                || id.contains("6digit") || id.contains("6-digit")
                || id.contains("2d") || name.contains("ez2");

        List<Integer> result = new ArrayList<>();
        if (isDigitGame) {
            // Digit games: random digits 0–9, repeats allowed
            for (int i = 0; i < required; i++) {
                hash = (hash + (hash << 13)) & 0xFFFFFFFFL;
                hash ^= (hash >> 7);
                hash = (hash + (hash << 3)) & 0xFFFFFFFFL;
                hash ^= (hash >> 17);
                hash = (hash + (hash << 5)) & 0xFFFFFFFFL;
                result.add((int)(hash % 10));
            }
        } else {
            // 6-number games: unique numbers 1–maxNumber
            Set<Integer> picked = new LinkedHashSet<>();
            while (picked.size() < required) {
                hash = (hash + (hash << 13)) & 0xFFFFFFFFL;
                hash ^= (hash >> 7);
                hash = (hash + (hash << 3)) & 0xFFFFFFFFL;
                hash ^= (hash >> 17);
                hash = (hash + (hash << 5)) & 0xFFFFFFFFL;
                int num = (int)(hash % game.getMaxNumber()) + 1;
                picked.add(num);
            }
            result = new ArrayList<>(picked);
            Collections.sort(result);
        }
        return result;
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

    private BigDecimal computePayout(int matches, BigDecimal stake) {
        return switch (matches) {
            case 6 -> stake.multiply(new BigDecimal("50000"));
            case 5 -> stake.multiply(new BigDecimal("5000"));
            case 4 -> stake.multiply(new BigDecimal("500"));
            case 3 -> stake.multiply(new BigDecimal("50"));
            default -> BigDecimal.ZERO;
        };
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
        m.put("drawTime", bet.getDrawTime());
        m.put("placedAt", bet.getPlacedAt());
        m.put("status", bet.getStatus());
        m.put("matches", bet.getMatches());
        m.put("payout", bet.getPayout());
        m.put("officialNumbers", bet.getOfficialNumbers() != null ? stringToNumbers(bet.getOfficialNumbers()) : null);
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

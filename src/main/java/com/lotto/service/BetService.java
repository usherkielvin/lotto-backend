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
        if (numbers == null || numbers.size() != 6) {
            throw new RuntimeException("Exactly 6 numbers required.");
        }

        LottoGame game = gameRepo.findById(gameId)
                .orElseThrow(() -> new RuntimeException("Game not found."));

        String drawDateKey = nextDrawDateKey();

        Bet bet = new Bet();
        bet.setId(System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6));
        bet.setUserId(userId);
        bet.setGameId(gameId);
        bet.setGameName(game.getName());
        Collections.sort(numbers);
        bet.setNumbers(numbersToString(numbers));
        bet.setStake(stake);
        bet.setDrawDateKey(drawDateKey);
        bet.setPlacedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")));
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

    // ── Settle pending bets past 9 PM draw time ────────────────────────────────

    private void settleIfNeeded(@NonNull Long userId) {
        List<Bet> pending = betRepo.findByUserIdAndStatusOrderByPlacedAtDesc(userId, "pending");
        LocalDateTime now = LocalDateTime.now();
        BigDecimal totalPayout = BigDecimal.ZERO;
        boolean anySettled = false;

        for (Bet bet : pending) {
            LocalDate drawDate = LocalDate.parse(bet.getDrawDateKey());
            LocalDateTime drawTime = LocalDateTime.of(drawDate, LocalTime.of(21, 0));
            if (now.isBefore(drawTime)) continue;

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

        Set<Integer> picked = new LinkedHashSet<>();
        while (picked.size() < 6) {
            hash = (hash + (hash << 13)) & 0xFFFFFFFFL;
            hash ^= (hash >> 7);
            hash = (hash + (hash << 3)) & 0xFFFFFFFFL;
            hash ^= (hash >> 17);
            hash = (hash + (hash << 5)) & 0xFFFFFFFFL;
            int num = (int)(hash % game.getMaxNumber()) + 1;
            picked.add(num);
        }

        List<Integer> result = new ArrayList<>(picked);
        Collections.sort(result);
        return result;
    }

    private int countMatches(List<Integer> picked, List<Integer> official) {
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

    private String nextDrawDateKey() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        LocalDateTime todayDraw = LocalDateTime.of(today, LocalTime.of(21, 0));
        LocalDate drawDate = now.isBefore(todayDraw) ? today : today.plusDays(1);
        return drawDate.toString(); // yyyy-MM-dd
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
        m.put("placedAt", bet.getPlacedAt());
        m.put("status", bet.getStatus());
        m.put("matches", bet.getMatches());
        m.put("payout", bet.getPayout());
        m.put("officialNumbers", bet.getOfficialNumbers() != null ? stringToNumbers(bet.getOfficialNumbers()) : null);
        return m;
    }
}

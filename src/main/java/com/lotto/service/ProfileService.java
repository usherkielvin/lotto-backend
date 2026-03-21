package com.lotto.service;

import com.lotto.entity.Bet;
import com.lotto.entity.Balance;
import com.lotto.entity.User;
import com.lotto.repository.BalanceRepository;
import com.lotto.repository.BetRepository;
import com.lotto.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.lang.NonNull;

import java.math.BigDecimal;
import java.util.*;

@Service
public class ProfileService {

    private final BetRepository betRepo;
    private final BalanceRepository balanceRepo;
    private final UserRepository userRepo;

    public ProfileService(BetRepository betRepo, BalanceRepository balanceRepo, UserRepository userRepo) {
        this.betRepo = betRepo;
        this.balanceRepo = balanceRepo;
        this.userRepo = userRepo;
    }

    public Map<String, Object> getProfile(@NonNull Long userId) {
        User user = userRepo.findById(userId).orElseThrow(() -> new RuntimeException("User not found."));
        List<Bet> allBets = betRepo.findByUserIdOrderByPlacedAtDesc(userId);

        int totalPlays = allBets.size();
        long prizesWon = allBets.stream().filter(b -> "won".equals(b.getStatus())).count();
        int bestMatch = allBets.stream().filter(b -> b.getMatches() != null).mapToInt(Bet::getMatches).max().orElse(0);
        int winRate = totalPlays > 0 ? (int) Math.round(prizesWon * 100.0 / totalPlays) : 0;

        BigDecimal balance = balanceRepo.findById(userId).map(Balance::getAmount).orElse(BigDecimal.ZERO);

        // Most frequently picked numbers (used as "lucky numbers")
        Map<Integer, Integer> freq = new HashMap<>();
        for (Bet b : allBets) {
            for (String n : b.getNumbers().split(",")) {
                int num = Integer.parseInt(n.trim());
                freq.merge(num, 1, (x, y) -> x + y);
            }
        }
        List<Integer> luckyNumbers;
        if (freq.size() >= 6) {
            luckyNumbers = freq.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                    .limit(6)
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
        } else {
            luckyNumbers = Arrays.asList(7, 14, 22, 33, 40, 49);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName() != null ? user.getDisplayName() : user.getUsername());
        result.put("memberSince", user.getCreatedAt() != null
                ? user.getCreatedAt().getMonth().name().substring(0, 1)
                  + user.getCreatedAt().getMonth().name().substring(1).toLowerCase()
                  + " " + user.getCreatedAt().getYear()
                : "");
        result.put("balance", balance);
        result.put("totalPlays", totalPlays);
        result.put("prizesWon", prizesWon);
        result.put("bestMatch", bestMatch);
        result.put("winRate", winRate + "%");
        result.put("luckyNumbers", luckyNumbers);
        return result;
    }
}

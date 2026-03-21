package com.lotto.service;

import com.lotto.entity.Bet;
import com.lotto.entity.Balance;
import com.lotto.entity.User;
import com.lotto.repository.BalanceRepository;
import com.lotto.repository.BetRepository;
import com.lotto.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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

    public Map<String, Object> updateProfile(@NonNull Long userId, Map<String, String> body) {
        User user = userRepo.findById(userId).orElseThrow(() -> new RuntimeException("User not found."));

        String displayName = body.get("displayName");
        if (displayName != null) {
            String trimmed = displayName.trim();
            if (trimmed.isEmpty()) throw new RuntimeException("Display name cannot be empty.");
            if (trimmed.length() > 32) throw new RuntimeException("Display name must be 32 characters or fewer.");
            user.setDisplayName(trimmed);
        }

        String newUsername = body.get("username");
        if (newUsername != null) {
            String clean = newUsername.trim().toLowerCase();
            if (clean.isEmpty()) throw new RuntimeException("Username cannot be empty.");
            if (clean.length() < 3) throw new RuntimeException("Username must be at least 3 characters.");
            if (clean.length() > 24) throw new RuntimeException("Username must be 24 characters or fewer.");
            if (!clean.matches("[a-z0-9._-]+")) throw new RuntimeException("Username can only contain letters, numbers, dots, hyphens and underscores.");
            if (!clean.equals(user.getUsername()) && userRepo.findByUsername(clean).isPresent()) {
                throw new RuntimeException("Username already taken.");
            }
            user.setUsername(clean);
        }

        String avatarUrl = body.get("avatarUrl");
        if (avatarUrl != null) {
            user.setAvatarUrl(avatarUrl.isEmpty() ? null : avatarUrl);
        }

        userRepo.save(user);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName() != null ? user.getDisplayName() : user.getUsername());
        result.put("avatarUrl", user.getAvatarUrl());
        return result;
    }

    public void changePassword(@NonNull Long userId, String currentPassword, String newPassword) {
        User user = userRepo.findById(userId).orElseThrow(() -> new RuntimeException("User not found."));

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        if (!encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new RuntimeException("Current password is incorrect.");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new RuntimeException("New password must be at least 6 characters.");
        }
        user.setPasswordHash(encoder.encode(newPassword));
        userRepo.save(user);
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
        result.put("avatarUrl", user.getAvatarUrl());
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

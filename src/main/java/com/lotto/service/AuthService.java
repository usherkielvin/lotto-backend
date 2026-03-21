package com.lotto.service;

import com.lotto.entity.Balance;
import com.lotto.entity.User;
import com.lotto.repository.BalanceRepository;
import com.lotto.repository.UserRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepo;
    private final BalanceRepository balanceRepo;
    private final BCryptPasswordEncoder encoder;

    public AuthService(UserRepository userRepo, BalanceRepository balanceRepo, BCryptPasswordEncoder encoder) {
        this.userRepo = userRepo;
        this.balanceRepo = balanceRepo;
        this.encoder = encoder;
    }

    public Map<String, Object> login(String username, String password) {
        Optional<User> opt = userRepo.findByUsername(username.trim().toLowerCase());
        if (opt.isEmpty()) {
            throw new RuntimeException("Invalid username or password.");
        }
        User user = opt.get();
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw new RuntimeException("Invalid username or password.");
        }
        return buildSession(user, false);
    }

    public Map<String, Object> demoLogin() {
        User user = userRepo.findByUsername("demo-player")
                .orElseThrow(() -> new RuntimeException("Demo user not found."));
        return buildSession(user, true);
    }

    public Map<String, Object> register(String username, String password, String displayName) {
        String clean = username.trim().toLowerCase();
        if (userRepo.findByUsername(clean).isPresent()) {
            throw new RuntimeException("Username already taken.");
        }
        User user = new User();
        user.setUsername(clean);
        user.setPasswordHash(encoder.encode(password));
        user.setDisplayName(displayName != null && !displayName.isBlank() ? displayName : clean);
        user = userRepo.save(user);

        Balance balance = new Balance(user.getId(), new BigDecimal("0.00"));
        balanceRepo.save(balance);

        return buildSession(user, false);
    }

    private Map<String, Object> buildSession(User user, boolean isDemo) {
        Map<String, Object> result = new HashMap<>();
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName() != null ? user.getDisplayName() : user.getUsername());
        result.put("role", user.getRole());
        result.put("demo", isDemo);
        return result;
    }
}

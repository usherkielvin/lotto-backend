package com.lotto.controller;

import com.lotto.entity.Balance;
import com.lotto.entity.FundingTransaction;
import com.lotto.repository.BalanceRepository;
import com.lotto.repository.FundingTransactionRepository;
import com.lotto.service.BetService;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bets")
public class BetController {

    private final BetService betService;
    private final BalanceRepository balanceRepo;
    private final FundingTransactionRepository fundingRepo;

    public BetController(BetService betService, BalanceRepository balanceRepo, FundingTransactionRepository fundingRepo) {
        this.betService = betService;
        this.balanceRepo = balanceRepo;
        this.fundingRepo = fundingRepo;
    }

    /** Place a new bet */
    @PostMapping
    public ResponseEntity<?> placeBet(@RequestHeader("X-User-Id") @NonNull Long userId,
                                       @RequestBody Map<String, Object> body) {
        try {
            String gameId = (String) body.get("gameId");
            if (gameId == null) throw new RuntimeException("gameId is required.");
            @SuppressWarnings("unchecked")
            List<Integer> numbers = (List<Integer>) body.get("numbers");
            BigDecimal stake = new BigDecimal(body.get("stake").toString());
            return ResponseEntity.ok(betService.placeBet(userId, gameId, numbers, stake));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Active (pending) tickets */
    @GetMapping
    public ResponseEntity<?> getActiveBets(@RequestHeader("X-User-Id") @NonNull Long userId) {
        try {
            return ResponseEntity.ok(betService.getActiveBets(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Settled bet history */
    @GetMapping("/history")
    public ResponseEntity<?> getBetHistory(@RequestHeader("X-User-Id") @NonNull Long userId) {
        try {
            return ResponseEntity.ok(betService.getBetHistory(userId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Current balance */
    @GetMapping("/balance")
    public ResponseEntity<?> getBalance(@RequestHeader("X-User-Id") @NonNull Long userId) {
        Balance balance = balanceRepo.findById(userId).orElse(null);
        if (balance == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("balance", balance.getAmount()));
    }

    /** Deposit or withdraw demo credits */
    @PostMapping("/balance")
    public ResponseEntity<?> adjustBalance(@RequestHeader("X-User-Id") @NonNull Long userId,
                                           @RequestBody Map<String, Object> body) {
        try {
            String type = (String) body.get("type");
            BigDecimal amount = new BigDecimal(body.get("amount").toString());
            if (amount.compareTo(new BigDecimal("50")) < 0) throw new RuntimeException("Minimum amount is ₱50.");

            Balance balance = balanceRepo.findById(userId)
                    .orElseThrow(() -> new RuntimeException("Balance not found."));

            if ("deposit".equals(type)) {
                balance.setAmount(balance.getAmount().add(amount));
            } else if ("withdraw".equals(type)) {
                if (balance.getAmount().compareTo(amount) < 0) throw new RuntimeException("Insufficient balance.");
                balance.setAmount(balance.getAmount().subtract(amount));
            } else {
                throw new RuntimeException("Invalid type. Use 'deposit' or 'withdraw'.");
            }

            balanceRepo.save(balance);

            FundingTransaction tx = new FundingTransaction();
            tx.setUserId(userId);
            tx.setType(type);
            tx.setAmount(amount);
            tx.setBalanceAfter(balance.getAmount());
            tx.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")));
            fundingRepo.save(tx);

            return ResponseEntity.ok(Map.of("balance", balance.getAmount()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Funding transaction history */
    @GetMapping("/funding")
    public ResponseEntity<?> getFundingHistory(@RequestHeader("X-User-Id") @NonNull Long userId) {
        List<FundingTransaction> txs = fundingRepo.findByUserIdOrderByIdDesc(userId);
        List<Map<String, Object>> result = txs.stream().map(tx -> Map.<String, Object>of(
                "id",           tx.getId(),
                "type",         tx.getType(),
                "amount",       tx.getAmount(),
                "balanceAfter", tx.getBalanceAfter(),
                "createdAt",    tx.getCreatedAt()
        )).toList();
        return ResponseEntity.ok(result);
    }
}

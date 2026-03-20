package com.lotto.repository;

import com.lotto.entity.FundingTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FundingTransactionRepository extends JpaRepository<FundingTransaction, Long> {
    List<FundingTransaction> findByUserIdOrderByIdDesc(Long userId);
}

package com.lotto.repository;

import com.lotto.entity.OfficialResult;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface OfficialResultRepository extends JpaRepository<OfficialResult, Long> {
    Optional<OfficialResult> findByGameIdAndDrawDateKey(String gameId, String drawDateKey);
    Optional<OfficialResult> findByGameIdAndDrawDateKeyAndDrawTime(String gameId, String drawDateKey, String drawTime);
    java.util.List<OfficialResult> findByGameIdOrderByDrawDateKeyDesc(String gameId);
}

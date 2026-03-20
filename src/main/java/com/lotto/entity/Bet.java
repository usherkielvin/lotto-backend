package com.lotto.entity;

import jakarta.persistence.*;
import org.springframework.lang.NonNull;
import java.math.BigDecimal;

@Entity
@Table(name = "bets")
public class Bet {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "game_id", nullable = false)
    @NonNull private String gameId = "";

    @Column(name = "game_name", nullable = false)
    private String gameName;

    /** Comma-separated numbers e.g. "3,12,24,33,41,55" */
    @Column(nullable = false)
    private String numbers;

    @Column(nullable = false)
    private BigDecimal stake;

    @Column(name = "draw_date_key", nullable = false)
    private String drawDateKey;

    @Column(name = "draw_time", nullable = false)
    private String drawTime = "9:00 PM";

    @Column(name = "placed_at", nullable = false)
    private String placedAt;

    @Column(nullable = false)
    private String status = "pending";

    private Integer matches;
    private BigDecimal payout;

    @Column(name = "official_numbers")
    private String officialNumbers;

    public Bet() {}

    // Getters & Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    @NonNull public String getGameId() { return gameId; }
    public void setGameId(@NonNull String gameId) { this.gameId = gameId; }
    public String getGameName() { return gameName; }
    public void setGameName(String gameName) { this.gameName = gameName; }
    public String getNumbers() { return numbers; }
    public void setNumbers(String numbers) { this.numbers = numbers; }
    public BigDecimal getStake() { return stake; }
    public void setStake(BigDecimal stake) { this.stake = stake; }
    public String getDrawDateKey() { return drawDateKey; }
    public void setDrawDateKey(String drawDateKey) { this.drawDateKey = drawDateKey; }
    public String getDrawTime() { return drawTime; }
    public void setDrawTime(String drawTime) { this.drawTime = drawTime; }
    public String getPlacedAt() { return placedAt; }
    public void setPlacedAt(String placedAt) { this.placedAt = placedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getMatches() { return matches; }
    public void setMatches(Integer matches) { this.matches = matches; }
    public BigDecimal getPayout() { return payout; }
    public void setPayout(BigDecimal payout) { this.payout = payout; }
    public String getOfficialNumbers() { return officialNumbers; }
    public void setOfficialNumbers(String officialNumbers) { this.officialNumbers = officialNumbers; }
}

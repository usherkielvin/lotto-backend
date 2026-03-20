package com.lotto.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "official_results")
public class OfficialResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "game_id", nullable = false)
    private String gameId;

    @Column(name = "draw_date_key", nullable = false)
    private String drawDateKey;

    @Column(name = "draw_time", nullable = false)
    private String drawTime = "9:00 PM";

    @Column(nullable = false)
    private String numbers;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public OfficialResult() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public String getDrawDateKey() { return drawDateKey; }
    public void setDrawDateKey(String drawDateKey) { this.drawDateKey = drawDateKey; }
    public String getDrawTime() { return drawTime; }
    public void setDrawTime(String drawTime) { this.drawTime = drawTime; }
    public String getNumbers() { return numbers; }
    public void setNumbers(String numbers) { this.numbers = numbers; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

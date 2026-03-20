package com.lotto.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "lotto_games")
public class LottoGame {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "max_number", nullable = false)
    private int maxNumber;

    @Column(name = "draw_time", nullable = false)
    private String drawTime;

    /** Comma-separated day numbers: 1=Mon,2=Tue,3=Wed,4=Thu,5=Fri,6=Sat,7=Sun */
    @Column(name = "draw_days", nullable = false)
    private String drawDays;

    @Column(name = "jackpot", nullable = false)
    private long jackpot;

    @Column(name = "jackpot_status", nullable = false)
    private String jackpotStatus;

    public LottoGame() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getMaxNumber() { return maxNumber; }
    public void setMaxNumber(int maxNumber) { this.maxNumber = maxNumber; }
    public String getDrawTime() { return drawTime; }
    public void setDrawTime(String drawTime) { this.drawTime = drawTime; }
    public String getDrawDays() { return drawDays; }
    public void setDrawDays(String drawDays) { this.drawDays = drawDays; }
    public long getJackpot() { return jackpot; }
    public void setJackpot(long jackpot) { this.jackpot = jackpot; }
    public String getJackpotStatus() { return jackpotStatus; }
    public void setJackpotStatus(String jackpotStatus) { this.jackpotStatus = jackpotStatus; }
}

package com.lotto.controller;

import com.lotto.entity.LottoGame;
import com.lotto.repository.LottoGameRepository;
import com.lotto.repository.OfficialResultRepository;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final LottoGameRepository gameRepo;
    private final OfficialResultRepository resultRepo;

    public GameController(LottoGameRepository gameRepo, OfficialResultRepository resultRepo) {
        this.gameRepo = gameRepo;
        this.resultRepo = resultRepo;
    }

    @GetMapping
    public List<LottoGame> listGames(@RequestParam(required = false) String day) {
        List<LottoGame> all = gameRepo.findAll();
        if (day == null) return all;

        // day param: "today" or a day number 1-7 (Mon=1 … Sun=7)
        int dayNum;
        if ("today".equalsIgnoreCase(day)) {
            DayOfWeek dow = LocalDate.now().getDayOfWeek();
            dayNum = dow.getValue(); // Mon=1 … Sun=7
        } else {
            try { dayNum = Integer.parseInt(day); } catch (NumberFormatException e) { return all; }
        }

        final int d = dayNum;
        return all.stream().filter(g -> {
            Set<Integer> days = Arrays.stream(g.getDrawDays().split(","))
                    .map(String::trim).map(Integer::parseInt)
                    .collect(Collectors.toSet());
            return days.contains(d);
        }).collect(Collectors.toList());
    }

    /** Returns each game with all draw results (newest first) and winner counts. */
    @GetMapping("/results")
    public List<Map<String, Object>> gameResults() {
        List<LottoGame> games = gameRepo.findAll();
        List<Map<String, Object>> out = new ArrayList<>();

        for (LottoGame game : games) {
            List<com.lotto.entity.OfficialResult> results =
                resultRepo.findByGameIdOrderByDrawDateKeyDesc(game.getId());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id",           game.getId());
            row.put("name",         game.getName());
            row.put("jackpot",      game.getJackpot());
            row.put("jackpotStatus",game.getJackpotStatus());
            row.put("drawTime",     game.getDrawTime());
            row.put("drawDays",     game.getDrawDays());
            row.put("maxNumber",    game.getMaxNumber());

            List<Map<String, Object>> drawResults = new ArrayList<>();
            for (com.lotto.entity.OfficialResult r : results) {
                Map<String, Object> dr = new LinkedHashMap<>();
                dr.put("drawDateKey", r.getDrawDateKey());
                dr.put("drawTime",    r.getDrawTime());
                dr.put("numbers",     r.getNumbers());
                dr.put("winners",     r.getWinners() != null ? r.getWinners() : 0);
                if (r.getJackpot() != null) dr.put("jackpot", r.getJackpot());
                drawResults.add(dr);
            }
            row.put("results", drawResults);

            out.add(row);
        }
        return out;
    }
}

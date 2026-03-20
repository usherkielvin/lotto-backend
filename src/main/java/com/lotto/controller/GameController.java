package com.lotto.controller;

import com.lotto.entity.LottoGame;
import com.lotto.repository.LottoGameRepository;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final LottoGameRepository gameRepo;

    public GameController(LottoGameRepository gameRepo) {
        this.gameRepo = gameRepo;
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
}

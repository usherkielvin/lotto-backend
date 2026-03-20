package com.lotto.service;

import com.lotto.entity.OfficialResult;
import com.lotto.repository.LottoGameRepository;
import com.lotto.repository.OfficialResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

@Service
public class PcsoScraperService {

    private static final Logger log = LoggerFactory.getLogger(PcsoScraperService.class);

    // Matches combinations like "9-4-7", "0 - 6 - 2", "11,14", "5 5 6 4", "29-52-49-55-25-09"
    private static final Pattern COMBO_PATTERN =
        Pattern.compile("\\d{1,2}(?:\\s*[- ,]\\s*\\d{1,2}){1,5}");

    // Matches draw times like "2:00 PM", "5:00 PM", "9:00 PM", "10:30 AM" or "2pm", "5PM"
    private static final Pattern TIME_PATTERN =
        Pattern.compile("(\\d{1,2}(?::\\d{2})?\\s*(?:AM|PM))", Pattern.CASE_INSENSITIVE);

    // Matches inline multi-draw pairs: group(1)=time token, group(2)=number combo
    // The combo group uses a negative lookahead to avoid consuming digits that are part of a time token (e.g., "5PM")
    private static final Pattern INLINE_DRAW_PATTERN = Pattern.compile(
        "(\\d{1,2}(?::\\d{2})?\\s*(?:AM|PM))\\s*:?\\s*(\\d{1,2}(?:[-,]\\d{1,2}){1,5})(?!\\s*(?:AM|PM))",
        Pattern.CASE_INSENSITIVE
    );

    // Matches dates in various formats
    private static final DateTimeFormatter LONG_DATE =
        DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DATE =
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter ISO_DATE =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH);

    private final OfficialResultRepository resultRepo;

    public PcsoScraperService(OfficialResultRepository resultRepo, LottoGameRepository gameRepo) {
        this.resultRepo = resultRepo;
    }

    @Transactional
    public Map<String, Object> importManualResults(String rawData) {
        int updatedCount = 0;
        List<String> errors = new ArrayList<>();

        // --- Pass 1: build game context blocks ---
        String[] lines = rawData.split("\\n");

        // Each block: gameId, date (nullable), lines
        List<String[]> blockGameIds = new ArrayList<>();
        List<String[]> blockDates = new ArrayList<>();   // single-element array so we can mutate
        List<List<String>> blockLines = new ArrayList<>();

        int currentBlock = -1;

        for (String raw : lines) {
            String text = raw.trim();
            if (text.isEmpty()) continue;

            String gid = mapToGameId(text);
            if (gid != null) {
                // Start a new block
                blockGameIds.add(new String[]{gid});
                blockDates.add(new String[]{tryParseDateFromText(text)});
                List<String> lines2 = new ArrayList<>();
                // Also add the game-name line itself so inline draws on the same line are processed
                lines2.add(text);
                blockLines.add(lines2);
                currentBlock = blockGameIds.size() - 1;
            } else if (currentBlock >= 0) {
                blockLines.get(currentBlock).add(text);
                if (blockDates.get(currentBlock)[0] == null) {
                    String d = tryParseDateFromText(text);
                    if (d != null) {
                        blockDates.get(currentBlock)[0] = d;
                    }
                }
            }
        }

        // Fill null dates with today
        for (String[] dateHolder : blockDates) {
            if (dateHolder[0] == null) {
                dateHolder[0] = LocalDate.now().toString();
            }
        }

        // --- Pass 2: extract results from each block ---
        for (int b = 0; b < blockGameIds.size(); b++) {
            String gameId = blockGameIds.get(b)[0];
            String date = blockDates.get(b)[0];

            for (String line : blockLines.get(b)) {
                Matcher inlineMatcher = INLINE_DRAW_PATTERN.matcher(line);
                boolean foundInline = false;

                // Collect all inline matches first to check if any exist
                while (inlineMatcher.find()) {
                    foundInline = true;
                    String timeToken = inlineMatcher.group(1);
                    String rawCombo = inlineMatcher.group(2);
                    String numbers = normalizeNumbers(rawCombo);
                    int count = numbers.split(",").length;
                    if (isValidCount(gameId, count)) {
                        String drawTime = extractTime(timeToken);
                        if (drawTime != null) {
                            updatedCount += saveResult(gameId, date, drawTime, numbers, errors);
                        }
                    }
                }

                if (!foundInline) {
                    // Fall back to single-draw extraction
                    Matcher comboMatcher = COMBO_PATTERN.matcher(line);
                    if (comboMatcher.find()) {
                        String numbers = normalizeNumbers(comboMatcher.group());
                        int count = numbers.split(",").length;
                        if (isValidCount(gameId, count)) {
                            String drawTime = extractTime(line);
                            if (drawTime == null && !isMultiDraw(gameId)) {
                                drawTime = "9:00 PM";
                            }
                            if (drawTime != null) {
                                updatedCount += saveResult(gameId, date, drawTime, numbers, errors);
                            }
                        }
                    }
                }
            }
        }

        Map<String, Object> r = new HashMap<>();
        r.put("updatedCount", updatedCount);
        r.put("errors", errors);
        r.put("status", updatedCount > 0 ? "success" : "failed");
        return r;
    }

    private String normalizeNumbers(String rawCombo) {
        return rawCombo.replaceAll("[^0-9]", ",")
                       .replaceAll(",+", ",")
                       .replaceAll("^,", "")
                       .replaceAll(",$", "");
    }

    @Transactional
    public int saveResult(String gameId, String drawDateKey, String drawTime, String numbers, List<String> errors) {
        try {
            Optional<OfficialResult> existing =
                resultRepo.findByGameIdAndDrawDateKeyAndDrawTime(gameId, drawDateKey, drawTime);
            if (existing.isPresent()) {
                log.info("Already exists: {} {} {}", gameId, drawDateKey, drawTime);
                return 0;
            }
            OfficialResult r = new OfficialResult();
            r.setGameId(gameId);
            r.setDrawDateKey(drawDateKey);
            r.setDrawTime(drawTime);
            r.setNumbers(numbers);
            resultRepo.save(r);
            log.info("Saved: {} {} {} → {}", gameId, drawDateKey, drawTime, numbers);
            return 1;
        } catch (Exception e) {
            log.error("Failed to save result: {} {} {} {}", gameId, drawDateKey, drawTime, numbers, e);
            errors.add("Save failed for " + gameId + " " + drawDateKey + " " + drawTime + ": " + e.getMessage());
            return 0;
        }
    }

    private String mapToGameId(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.toLowerCase().trim();
        if (t.length() > 150) return null;
        // Skip STL games
        if (t.contains("stl")) return null;

        if (t.matches(".*(6/58|ultra).*")) return "ultra-658";
        if (t.matches(".*(6/55|grand).*")) return "grand-655";
        if (t.matches(".*(6/49|super).*")) return "super-649";
        if (t.matches(".*(6/45|mega).*"))  return "mega-645";
        if (t.matches(".*(6/42|lotto).*") && !t.contains("6/45") && !t.contains("6/49") && !t.contains("6/55") && !t.contains("6/58")) return "lotto-642";

        if (t.matches(".*(6d|6 digit|6-digit).*")) return "6digit";
        if (t.matches(".*(4d|4 digit|4-digit).*")) return "4digit";

        if (t.matches(".*(3d|swertres|suertres).*")) return "3d-swertres";
        if (t.matches(".*(2d|ez2|ez-2).*"))      return "2d-ez2";

        return null;
    }

    private String tryParseDateFromText(String text) {
        if (text == null || text.isBlank()) return null;
        if (text.length() > 200) return null;

        String clean = text.replaceAll("[\\(\\):\\.]", " ")
                           .replaceAll("\\s+", " ")
                           .trim();

        String[] prefixes = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
                             "Draw Result", "PCSO Result", "Latest Result", "Result for", "Results for"};
        for (String p : prefixes) {
            clean = clean.replaceAll("(?i)^" + p + ",?\\s*", "");
        }
        clean = clean.trim();

        DateTimeFormatter[] formatters = {LONG_DATE, SHORT_DATE, ISO_DATE,
                                         DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH),
                                         DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH),
                                         DateTimeFormatter.ofPattern("M/d/yyyy", Locale.ENGLISH),
                                         DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH)};

        for (DateTimeFormatter fmt : formatters) {
            try {
                return LocalDate.parse(clean, fmt).toString();
            } catch (Exception e) {
                try {
                    String[] words = clean.split(" ");
                    for (int i = 0; i < words.length; i++) {
                        for (int j = i + 1; j <= words.length; j++) {
                            String sub = String.join(" ", Arrays.copyOfRange(words, i, j));
                            try {
                                return LocalDate.parse(sub, fmt).toString();
                            } catch (Exception ex) { /* continue */ }
                        }
                    }
                } catch (Exception ex) { /* continue */ }
            }
        }
        return null;
    }

    private String extractTime(String text) {
        if (text == null || text.isBlank()) return null;

        Matcher m = TIME_PATTERN.matcher(text);
        if (m.find()) {
            String raw = m.group();
            try {
                String t = raw.replaceAll("\\s+", " ").toUpperCase(Locale.ENGLISH).trim();

                if (t.matches("\\d{1,2}(?:AM|PM)")) {
                    t = t.substring(0, t.length() - 2) + ":00 " + t.substring(t.length() - 2);
                }
                if (t.matches("\\d{1,2}:\\d{2}(?:AM|PM)")) {
                    t = t.substring(0, t.length() - 2) + " " + t.substring(t.length() - 2);
                }
                if (t.matches("\\d{1,2}\\s+(?:AM|PM)")) {
                    t = t.replace(" ", ":00 ");
                }
                return t;
            } catch (Exception e) {
                return normalizeTime(raw);
            }
        }

        String t = text.toUpperCase(Locale.ENGLISH);
        if (t.contains("2PM") || t.contains("2 PM")) return "2:00 PM";
        if (t.contains("5PM") || t.contains("5 PM")) return "5:00 PM";
        if (t.contains("9PM") || t.contains("9 PM")) return "9:00 PM";

        return null;
    }

    private boolean isValidCount(String gameId, int count) {
        if (gameId.equals("ultra-658") || gameId.equals("grand-655") || gameId.equals("super-649") ||
            gameId.equals("mega-645") || gameId.equals("lotto-642")) return count == 6;
        if (gameId.equals("6digit")) return count == 6;
        if (gameId.equals("4digit")) return count == 4;
        if (gameId.equals("3d-swertres")) return count == 3;
        if (gameId.equals("2d-ez2")) return count == 2;
        return false;
    }

    private boolean isMultiDraw(String gameId) {
        return gameId != null && (gameId.equals("3d-swertres") || gameId.equals("2d-ez2"));
    }

    private String normalizeTime(String raw) {
        return raw.trim().toUpperCase(Locale.ENGLISH);
    }
}

package com.lotto.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DbMigrationConfig {

    private static final Logger log = LoggerFactory.getLogger(DbMigrationConfig.class);

    /**
     * Ensures the unique key on official_results includes draw_time.
     * The old key was (game_id, draw_date_key) — without draw_time —
     * which caused duplicate errors when saving 2PM/5PM/9PM for the same game+date.
     * This runs after schema.sql so it's safe to ALTER the live table.
     */
    @Bean
    public ApplicationRunner fixOfficialResultsUniqueKey(JdbcTemplate jdbc) {
        return args -> {
            try {
                // Check if draw_time is part of the unique key
                Integer hasDrawTimeInKey = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                    "WHERE TABLE_SCHEMA = DATABASE() " +
                    "  AND TABLE_NAME = 'official_results' " +
                    "  AND INDEX_NAME = 'unique_game_draw' " +
                    "  AND COLUMN_NAME = 'draw_time'",
                    Integer.class
                );

                if (hasDrawTimeInKey == null || hasDrawTimeInKey == 0) {
                    log.warn("official_results unique key is missing draw_time — fixing...");
                    jdbc.execute("ALTER TABLE official_results DROP INDEX unique_game_draw");
                    jdbc.execute("ALTER TABLE official_results ADD UNIQUE KEY unique_game_draw (game_id, draw_date_key, draw_time)");
                    log.info("official_results unique key updated to (game_id, draw_date_key, draw_time)");
                } else {
                    log.info("official_results unique key is correct — no migration needed");
                }
            } catch (Exception e) {
                log.error("Failed to migrate official_results unique key", e);
            }
        };
    }
}

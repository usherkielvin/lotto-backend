-- ─── Tables ───────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100),
    role VARCHAR(20) NOT NULL DEFAULT 'user',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS balances (
    user_id BIGINT PRIMARY KEY,
    amount DECIMAL(15,2) NOT NULL DEFAULT 5000.00,
    CONSTRAINT fk_balance_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS lotto_games (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    max_number INT NOT NULL,
    draw_time VARCHAR(50) NOT NULL,
    draw_days VARCHAR(20) NOT NULL DEFAULT '1,2,3,4,5,6,7',
    jackpot BIGINT NOT NULL DEFAULT 0,
    jackpot_status VARCHAR(150) NOT NULL DEFAULT 'Accumulating'
);

CREATE TABLE IF NOT EXISTS bets (
    id VARCHAR(80) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    game_id VARCHAR(50) NOT NULL,
    game_name VARCHAR(100) NOT NULL,
    numbers VARCHAR(100) NOT NULL,
    stake DECIMAL(10,2) NOT NULL,
    draw_date_key VARCHAR(20) NOT NULL,
    draw_time VARCHAR(20) NOT NULL DEFAULT '9:00 PM',
    placed_at VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    matches INT,
    payout DECIMAL(15,2),
    official_numbers VARCHAR(100),
    claimed BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_bet_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS official_results (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    game_id VARCHAR(50) NOT NULL,
    draw_date_key VARCHAR(20) NOT NULL,
    draw_time VARCHAR(20) NOT NULL DEFAULT '9:00 PM',
    numbers VARCHAR(100) NOT NULL,
    jackpot BIGINT,
    winners INT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY unique_game_draw (game_id, draw_date_key, draw_time)
);

CREATE TABLE IF NOT EXISTS funding_transactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    amount DECIMAL(15,2) NOT NULL,
    balance_after DECIMAL(15,2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ─── Fresh start: wipe transactional data on every startup ───────────────────
-- Users, balances, and official_results persist across restarts.
-- Only bets and funding history reset (simulator transactional data).

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE bets;
TRUNCATE TABLE funding_transactions;
SET FOREIGN_KEY_CHECKS = 1;

-- ─── Seed: admin + demo accounts ─────────────────────────────────────────────
-- Password: "password" (bcrypt) — INSERT IGNORE so existing accounts survive restarts
INSERT IGNORE INTO users (username, password_hash, display_name, role) VALUES
    ('admin',       '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2.uheWG/igi', 'Administrator', 'admin'),
    ('demo-player', '$2a$10$92IXUNpkjO0rOQ5byMi.Ye4oKoEa3Ro9llC/.og/at2.uheWG/igi', 'Demo Player',   'user');

-- Seed demo balance only if not already present
INSERT IGNORE INTO balances (user_id, amount)
    SELECT id, 0.00 FROM users WHERE username = 'demo-player';

-- ─── Static lotto games ───────────────────────────────────────────────────────
-- draw_days: 1=Mon 2=Tue 3=Wed 4=Thu 5=Fri 6=Sat 7=Sun
-- Ultra 6/58  : Tue,Fri,Sun  = 2,5,7
-- Grand 6/55  : Mon,Wed,Sat  = 1,3,6
-- Super 6/49  : Tue,Thu,Sun  = 2,4,7
-- Mega  6/45  : Mon,Wed,Fri  = 1,3,5
-- Lotto 6/42  : Tue,Thu,Sat  = 2,4,6
-- 6-Digit     : Tue,Thu,Sat  = 2,4,6
-- 4-Digit     : Mon,Wed,Fri  = 1,3,5
-- 3D Swertres : Daily        = 1,2,3,4,5,6,7
-- 2D EZ2      : Daily        = 1,2,3,4,5,6,7

INSERT INTO lotto_games (id, name, max_number, draw_time, draw_days, jackpot, jackpot_status) VALUES
    ('ultra-658',   'Ultra Lotto 6/58',    58,  '9:00 PM',                    '2,5,7',           75000000, 'Accumulating'),
    ('grand-655',   'Grand Lotto 6/55',    55,  '9:00 PM',                    '1,3,6',           45000000, 'Accumulating'),
    ('super-649',   'Super Lotto 6/49',    49,  '9:00 PM',                    '2,4,7',          124187419, 'Accumulating'),
    ('mega-645',    'Mega Lotto 6/45',     45,  '9:00 PM',                    '1,3,5',           15000000, 'Accumulating'),
    ('lotto-642',   'Lotto 6/42',          42,  '9:00 PM',                    '2,4,6',           10000000, 'Accumulating'),
    ('6digit',      '6-Digit Lotto',        9,  '9:00 PM',                    '2,4,6',            1909936, 'Accumulating'),
    ('4digit',      '4-Digit Lotto',     9999,  '9:00 PM',                    '1,3,5',              65244, 'Accumulating'),
    ('3d-swertres', '3D Lotto (Swertres)', 999, '2:00 PM, 5:00 PM, 9:00 PM', '1,2,3,4,5,6,7',      4500, 'P4,500 per P10 play'),
    ('2d-ez2',      '2D Lotto (EZ2)',       45, '2:00 PM, 5:00 PM, 9:00 PM', '1,2,3,4,5,6,7',      4000, 'P4,000 per P10 play')
ON DUPLICATE KEY UPDATE
    name           = VALUES(name),
    max_number     = VALUES(max_number),
    draw_time      = VALUES(draw_time),
    draw_days      = VALUES(draw_days),
    jackpot        = VALUES(jackpot),
    jackpot_status = VALUES(jackpot_status);

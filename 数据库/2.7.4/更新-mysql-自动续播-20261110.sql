-- MySQL 5.7+/8.x. Run on the existing WVP database; no existing table or data is replaced.
CREATE TABLE IF NOT EXISTS wvp_auto_play (
    server_id VARCHAR(64) NOT NULL,
    channel_id INT NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    check_interval_seconds INT NOT NULL DEFAULT 60,
    revision BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL DEFAULT 'PAUSED',
    failure_count INT NOT NULL DEFAULT 0,
    quiet_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(512) NOT NULL DEFAULT '',
    last_check_time BIGINT NOT NULL DEFAULT 0,
    next_check_time BIGINT NOT NULL DEFAULT 0,
    last_success_time BIGINT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(64) DEFAULT NULL,
    lease_until BIGINT NOT NULL DEFAULT 0,
    media_server_id VARCHAR(64) DEFAULT NULL,
    app VARCHAR(64) DEFAULT NULL,
    stream_id VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (server_id, channel_id),
    KEY idx_wvp_auto_play_due (server_id, enabled, next_check_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Rollback: use the previous application version. Keep this table for a later upgrade.

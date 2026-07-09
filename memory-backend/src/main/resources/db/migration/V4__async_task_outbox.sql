CREATE TABLE IF NOT EXISTS outbox_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_key VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 10,
    next_attempt_time DATETIME DEFAULT NULL,
    lock_token VARCHAR(128) DEFAULT NULL,
    locked_until DATETIME DEFAULT NULL,
    last_error VARCHAR(1000) DEFAULT NULL,
    published_at DATETIME DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_outbox_event_key (event_key),
    INDEX idx_outbox_status_next_id (status, next_attempt_time, id),
    INDEX idx_outbox_locked_until (status, locked_until),
    INDEX idx_outbox_aggregate (aggregate_type, aggregate_id)
);


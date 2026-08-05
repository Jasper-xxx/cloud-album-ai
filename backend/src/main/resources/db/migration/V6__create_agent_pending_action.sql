CREATE TABLE IF NOT EXISTS `agent_pending_action` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `pending_action_id` VARCHAR(36) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `family` VARCHAR(16) NOT NULL,
    `action` VARCHAR(64) NOT NULL,
    -- LONGTEXT intentionally preserves the exact serialized bytes covered by payload_hash.
    -- MySQL JSON normalizes object text and would invalidate a byte-level integrity hash.
    `payload_json` LONGTEXT NOT NULL,
    `payload_hash` CHAR(64) NOT NULL,
    `confirmation_token_hash` CHAR(64) NOT NULL,
    `idempotency_key_hash` CHAR(64) NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'PREVIEWED',
    `workflow_version` VARCHAR(32) NOT NULL,
    `preview_affected_file_count` INT NOT NULL DEFAULT 0,
    `preview_created_album_count` INT NOT NULL DEFAULT 0,
    `actual_affected_file_count` INT DEFAULT NULL,
    `actual_created_album_count` INT DEFAULT NULL,
    `skipped_file_count` INT DEFAULT NULL,
    `summary` VARCHAR(500) NOT NULL,
    `result_json` LONGTEXT DEFAULT NULL,
    `last_error` VARCHAR(500) DEFAULT NULL,
    `expires_at` DATETIME(3) NOT NULL,
    `confirmed_at` DATETIME(3) DEFAULT NULL,
    `started_at` DATETIME(3) DEFAULT NULL,
    `completed_at` DATETIME(3) DEFAULT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_pending_action_id` (`pending_action_id`),
    KEY `idx_agent_pending_user_status_expiry` (`user_id`, `status`, `expires_at`),
    KEY `idx_agent_pending_completed` (`status`, `completed_at`),
    CONSTRAINT `fk_agent_pending_user`
        FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  ROW_FORMAT=DYNAMIC;

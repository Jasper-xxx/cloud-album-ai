-- Legacy previews have no trustworthy conversation binding and cannot be claimed.
ALTER TABLE agent_pending_action
    ADD COLUMN conversation_id VARCHAR(128) COLLATE utf8mb4_bin NULL AFTER user_id,
    ADD INDEX idx_pending_conversation (user_id, conversation_id, status);

CREATE TABLE agent_conversation (
    user_id BIGINT NOT NULL,
    conversation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (user_id, conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

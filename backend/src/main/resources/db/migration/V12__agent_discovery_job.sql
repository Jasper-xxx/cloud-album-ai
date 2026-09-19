CREATE TABLE agent_discovery_job (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    status VARCHAR(16) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    result_json LONGTEXT NULL,
    error_message VARCHAR(300) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_discovery_user_created(user_id, created_at),
    CONSTRAINT fk_discovery_user FOREIGN KEY (user_id) REFERENCES `user`(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

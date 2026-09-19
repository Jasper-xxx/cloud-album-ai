CREATE TABLE agent_resource_grant (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    file_ids_json LONGTEXT NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (token_hash),
    KEY idx_grant_expiry (expires_at),
    CONSTRAINT fk_grant_user FOREIGN KEY (user_id) REFERENCES `user`(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

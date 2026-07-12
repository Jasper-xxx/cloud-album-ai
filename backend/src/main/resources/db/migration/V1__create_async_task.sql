CREATE TABLE IF NOT EXISTS async_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_key VARCHAR(190) NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    file_id VARCHAR(64) DEFAULT NULL,
    payload_json TEXT NULL,
    result_json TEXT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 5,
    next_retry_time DATETIME DEFAULT NULL,
    last_error VARCHAR(1000) DEFAULT NULL,
    started_at DATETIME DEFAULT NULL,
    completed_at DATETIME DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @column_exists := (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'async_task'
      AND column_name = 'result_json'
);
SET @ddl := IF(
    @column_exists = 0,
    'ALTER TABLE async_task ADD COLUMN result_json TEXT NULL AFTER payload_json',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'async_task'
      AND index_name = 'uk_async_task_key'
);
SET @ddl := IF(
    @index_exists = 0,
    'ALTER TABLE async_task ADD UNIQUE KEY uk_async_task_key (task_key)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'async_task'
      AND index_name = 'idx_async_task_due'
);
SET @ddl := IF(
    @index_exists = 0,
    'CREATE INDEX idx_async_task_due ON async_task (status, next_retry_time)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'async_task'
      AND index_name = 'idx_async_task_type_status'
);
SET @ddl := IF(
    @index_exists = 0,
    'CREATE INDEX idx_async_task_type_status ON async_task (task_type, status)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'async_task'
      AND index_name = 'idx_async_task_user_time'
);
SET @ddl := IF(
    @index_exists = 0,
    'CREATE INDEX idx_async_task_user_time ON async_task (user_id, create_time)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

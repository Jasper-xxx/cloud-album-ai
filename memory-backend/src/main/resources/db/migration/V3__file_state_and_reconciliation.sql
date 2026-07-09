SET @table_exists := (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'file');

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'file' AND column_name = 'status');
SET @ddl := IF(@table_exists > 0 AND @column_exists = 0, 'ALTER TABLE file ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''READY''', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'file' AND column_name = 'status_update_time');
SET @ddl := IF(@table_exists > 0 AND @column_exists = 0, 'ALTER TABLE file ADD COLUMN status_update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists := (SELECT COUNT(1) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'file' AND column_name = 'status_message');
SET @ddl := IF(@table_exists > 0 AND @column_exists = 0, 'ALTER TABLE file ADD COLUMN status_message VARCHAR(500) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'file' AND index_name = 'idx_file_status_update_time');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_file_status_update_time ON file (status, status_update_time, file_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'file' AND index_name = 'idx_file_object_status');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_file_object_status ON file (file_object_name(128), status, file_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'file' AND index_name = 'idx_file_thumbnail_object_status');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_file_thumbnail_object_status ON file (thumbnail_object_name(128), status, file_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;


SET @table_exists := (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'file');
SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'file' AND index_name = 'idx_file_md5');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_file_md5 ON file (md5)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'file' AND index_name = 'idx_file_category_datetime_id');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_file_category_datetime_id ON file (category, date_time_original, file_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'file' AND index_name = 'idx_file_last_modified_id');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_file_last_modified_id ON file (last_modified_time, file_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'file' AND index_name = 'idx_file_model_datetime');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_file_model_datetime ON file (make, model, date_time_original, file_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'file' AND index_name = 'idx_file_geo_pending');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_file_geo_pending ON file (location(32), file_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'file' AND index_name = 'idx_file_video_recovery');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_file_video_recovery ON file (category, thumbnail_object_name(64), file_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @table_exists := (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'user_file');
SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_file' AND index_name = 'idx_user_file_user_file_deleted');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_user_file_user_file_deleted ON user_file (user_id, file_id, is_deleted)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_file' AND index_name = 'idx_user_file_user_deleted_file_upload');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_user_file_user_deleted_file_upload ON user_file (user_id, is_deleted, file_id, upload_time)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_file' AND index_name = 'idx_user_file_file_user_deleted');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_user_file_file_user_deleted ON user_file (file_id, user_id, is_deleted)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'user_file' AND index_name = 'idx_user_file_deleted_time_file');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_user_file_deleted_time_file ON user_file (is_deleted, deleted_time, file_id, user_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @table_exists := (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'album_picture');
SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'album_picture' AND index_name = 'idx_album_picture_album_file');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_album_picture_album_file ON album_picture (album_id, file_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'album_picture' AND index_name = 'idx_album_picture_user_album_file');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_album_picture_user_album_file ON album_picture (user_id, album_id, file_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'album_picture' AND index_name = 'idx_album_picture_file_album');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_album_picture_file_album ON album_picture (file_id, album_id, user_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'album_picture' AND index_name = 'idx_album_picture_cover');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_album_picture_cover ON album_picture (album_id, is_cover, id, file_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @table_exists := (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'picture_tag');
SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'picture_tag' AND index_name = 'idx_picture_tag_file_tag');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_picture_tag_file_tag ON picture_tag (file_id, tag_name)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'picture_tag' AND index_name = 'idx_picture_tag_tag_file');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_picture_tag_tag_file ON picture_tag (tag_name, file_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'picture_tag' AND index_name = 'idx_picture_tag_image_type_tag_file');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_picture_tag_image_type_tag_file ON picture_tag (image_type, tag_name, file_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @table_exists := (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'async_task');
SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'async_task' AND index_name = 'idx_async_task_status_retry_time');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_async_task_status_retry_time ON async_task (status, next_retry_time, create_time, id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'async_task' AND index_name = 'idx_async_task_type_file_key');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_async_task_type_file_key ON async_task (task_type, file_id, task_key)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @table_exists := (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'face');
SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'face' AND index_name = 'idx_face_processed_id');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_face_processed_id ON face (is_processed, face_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'face' AND index_name = 'idx_face_user_file');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_face_user_file ON face (user_id, file_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'face' AND index_name = 'idx_face_file_user');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_face_file_user ON face (file_id, user_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @table_exists := (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'person_face');
SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'person_face' AND index_name = 'idx_person_face_user_person_rep_time');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_person_face_user_person_rep_time ON person_face (user_id, person_id, representative, update_time, face_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'person_face' AND index_name = 'idx_person_face_face_user');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_person_face_face_user ON person_face (face_id, user_id, person_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @table_exists := (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'location');
SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'location' AND index_name = 'idx_location_area_file');
SET @location_columns_exist := (
    SELECT COUNT(1)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'location'
      AND column_name IN ('country', 'province', 'city', 'district', 'file_id')
);
SET @country_prefix := (
    SELECT LEAST(COALESCE(character_maximum_length, 64), 64)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'location'
      AND column_name = 'country'
);
SET @province_prefix := (
    SELECT LEAST(COALESCE(character_maximum_length, 64), 64)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'location'
      AND column_name = 'province'
);
SET @city_prefix := (
    SELECT LEAST(COALESCE(character_maximum_length, 64), 64)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'location'
      AND column_name = 'city'
);
SET @district_prefix := (
    SELECT LEAST(COALESCE(character_maximum_length, 64), 64)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'location'
      AND column_name = 'district'
);
SET @ddl := IF(
    @table_exists > 0 AND @index_exists = 0 AND @location_columns_exist = 5,
    CONCAT(
        'CREATE INDEX idx_location_area_file ON location (country(', @country_prefix,
        '), province(', @province_prefix,
        '), city(', @city_prefix,
        '), district(', @district_prefix,
        '), file_id)'
    ),
    'SELECT 1'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @table_exists := (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'file_feature');
SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'file_feature' AND index_name = 'idx_file_feature_user_model_file');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_file_feature_user_model_file ON file_feature (user_id, feature_provider, feature_model, file_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'file_feature' AND index_name = 'idx_file_feature_file_user');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_file_feature_file_user ON file_feature (file_id, user_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @table_exists := (SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'similar_picture');
SET @index_exists := (SELECT COUNT(1) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'similar_picture' AND index_name = 'idx_similar_picture_user_file');
SET @ddl := IF(@table_exists > 0 AND @index_exists = 0, 'CREATE INDEX idx_similar_picture_user_file ON similar_picture (user_id, file_id, similar_id)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

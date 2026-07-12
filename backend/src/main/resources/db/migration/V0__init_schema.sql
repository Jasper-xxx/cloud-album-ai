-- Clean baseline schema for a fresh Cloud Album database.
-- This migration contains schema only: tables, indexes and foreign keys.
-- It intentionally does not include local dump data or environment-specific triggers.

CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_name` VARCHAR(50) NOT NULL,
    `account` VARCHAR(50) NOT NULL,
    `password` VARCHAR(255) NOT NULL,
    `email` VARCHAR(100) NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `profile` TEXT DEFAULT NULL,
    `avatar_url` VARCHAR(500) DEFAULT NULL,
    `avatar_object_name` VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_account` (`account`),
    UNIQUE KEY `uk_user_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `user_storage` (
    `user_id` BIGINT NOT NULL,
    `total_space` BIGINT NOT NULL DEFAULT 5368709120,
    `used_space` BIGINT NOT NULL DEFAULT 0,
    `account_status` ENUM('normal', 'vip', 'svip') NOT NULL DEFAULT 'normal',
    `membership_days` INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`user_id`),
    KEY `idx_account_status` (`account_status`),
    CONSTRAINT `fk_user_storage_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `file` (
    `file_id` VARCHAR(36) NOT NULL,
    `origin_file_name` VARCHAR(255) NOT NULL,
    `size` BIGINT NOT NULL,
    `last_modified_time` DATETIME NOT NULL,
    `content_type` VARCHAR(100) DEFAULT NULL,
    `category` VARCHAR(20) NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'READY',
    `status_update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `status_message` VARCHAR(500) DEFAULT NULL,
    `file_url` VARCHAR(500) NOT NULL,
    `thumbnail_url` VARCHAR(500) DEFAULT NULL,
    `file_object_name` VARCHAR(500) NOT NULL,
    `thumbnail_object_name` VARCHAR(500) DEFAULT NULL,
    `md5` VARCHAR(32) NOT NULL,
    `location` VARCHAR(200) DEFAULT NULL,
    `date_time_original` DATETIME DEFAULT NULL,
    `width` INT DEFAULT NULL,
    `height` INT DEFAULT NULL,
    `make` VARCHAR(255) DEFAULT ' ',
    `model` VARCHAR(255) DEFAULT ' ',
    `latitude` DOUBLE DEFAULT NULL,
    `latitude_ref` VARCHAR(2) DEFAULT NULL,
    `longitude` DOUBLE DEFAULT NULL,
    `longitude_ref` VARCHAR(2) DEFAULT NULL,
    PRIMARY KEY (`file_id`),
    KEY `idx_category` (`category`),
    KEY `idx_content_type` (`content_type`),
    KEY `file_date_time_original_index` (`date_time_original`),
    KEY `file_last_modified_time_index` (`last_modified_time`),
    KEY `file_location_index` (`location`),
    KEY `file_make_index` (`make`),
    KEY `file_model_index` (`model`),
    KEY `idx_file_md5` (`md5`),
    KEY `idx_file_category_datetime_id` (`category`, `date_time_original`, `file_id`),
    KEY `idx_file_last_modified_id` (`last_modified_time`, `file_id`),
    KEY `idx_file_model_datetime` (`make`, `model`, `date_time_original`, `file_id`),
    KEY `idx_file_geo_pending` (`location`(32), `file_id`),
    KEY `idx_file_video_recovery` (`category`, `thumbnail_object_name`(64), `file_id`),
    KEY `idx_file_status_update_time` (`status`, `status_update_time`),
    KEY `idx_file_object_status` (`file_object_name`(128), `status`, `file_id`),
    KEY `idx_file_thumbnail_object_status` (`thumbnail_object_name`(128), `status`, `file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `image_meta_data` (
    `file_id` VARCHAR(36) NOT NULL,
    `software` VARCHAR(255) DEFAULT NULL,
    `exposure_program` VARCHAR(50) DEFAULT NULL,
    `exposure_time` VARCHAR(20) DEFAULT NULL,
    `f_number` DOUBLE DEFAULT NULL,
    `iso` INT DEFAULT NULL,
    `focal_length` DOUBLE DEFAULT NULL,
    `focal_length35` DOUBLE DEFAULT NULL,
    `altitude` DOUBLE DEFAULT NULL,
    `altitude_ref` VARCHAR(10) DEFAULT NULL,
    `version` VARCHAR(20) DEFAULT NULL,
    `aperture_value` DOUBLE DEFAULT NULL,
    `shutter_speed` DOUBLE DEFAULT NULL,
    `metering_mode` VARCHAR(50) DEFAULT NULL,
    `white_balance` VARCHAR(50) DEFAULT NULL,
    `color_space` VARCHAR(50) DEFAULT NULL,
    `sensing_method` VARCHAR(50) DEFAULT NULL,
    `subject_distance` DOUBLE DEFAULT NULL,
    `scene_type` VARCHAR(50) DEFAULT NULL,
    PRIMARY KEY (`file_id`),
    CONSTRAINT `fk_image_meta_file` FOREIGN KEY (`file_id`) REFERENCES `file` (`file_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `video_meta_data` (
    `file_id` VARCHAR(36) NOT NULL,
    `color_space` VARCHAR(50) DEFAULT NULL,
    `duration` DOUBLE DEFAULT NULL,
    `fps` DOUBLE DEFAULT NULL,
    `video_codec_name` VARCHAR(255) DEFAULT NULL,
    `video_codec` INT DEFAULT NULL,
    `video_bitrate` INT DEFAULT NULL,
    `rotation` DOUBLE DEFAULT NULL,
    `audio_codec_name` VARCHAR(255) DEFAULT NULL,
    `audio_codec` INT DEFAULT NULL,
    `audio_sample_rate` INT DEFAULT NULL,
    `audio_channels` INT DEFAULT NULL,
    `copyright` VARCHAR(512) DEFAULT NULL,
    `language` VARCHAR(50) DEFAULT NULL,
    `profile` VARCHAR(100) DEFAULT NULL,
    `level` VARCHAR(50) DEFAULT NULL,
    `pixel_format` VARCHAR(50) DEFAULT NULL,
    PRIMARY KEY (`file_id`),
    KEY `idx_duration` (`duration`),
    CONSTRAINT `fk_video_meta_file` FOREIGN KEY (`file_id`) REFERENCES `file` (`file_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `location` (
    `file_id` VARCHAR(36) NOT NULL,
    `country` VARCHAR(50) DEFAULT NULL,
    `province` VARCHAR(50) DEFAULT NULL,
    `city` VARCHAR(50) DEFAULT NULL,
    `district` VARCHAR(50) DEFAULT NULL,
    `township` VARCHAR(50) DEFAULT NULL,
    `street` VARCHAR(50) DEFAULT NULL,
    `street_number` VARCHAR(20) DEFAULT NULL,
    `full_address` VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (`file_id`),
    KEY `idx_location_area_file` (`country`(50), `province`(50), `city`(50), `district`(50), `file_id`),
    CONSTRAINT `fk_location_file` FOREIGN KEY (`file_id`) REFERENCES `file` (`file_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `album` (
    `album_id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `album_name` VARCHAR(255) NOT NULL,
    `description` VARCHAR(255) DEFAULT NULL,
    `type` VARCHAR(20) NOT NULL DEFAULT 'normal',
    `tag_name` VARCHAR(50) DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `status` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`album_id`),
    KEY `idx_user` (`user_id`),
    KEY `idx_create_time` (`create_time`),
    KEY `idx_album_user_name` (`user_id`, `album_name`),
    KEY `idx_album_user_type_tag` (`user_id`, `type`, `tag_name`),
    CONSTRAINT `fk_album_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `album_picture` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `album_id` BIGINT NOT NULL,
    `file_id` VARCHAR(36) NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `is_cover` TINYINT(1) NOT NULL DEFAULT 0,
    `user_id` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_album_file` (`album_id`, `file_id`),
    KEY `idx_album_id` (`album_id`),
    KEY `idx_file_id` (`file_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_album_picture_album_file` (`album_id`, `file_id`),
    KEY `idx_album_picture_user_album_file` (`user_id`, `album_id`, `file_id`),
    KEY `idx_album_picture_file_album` (`file_id`, `album_id`, `user_id`),
    KEY `idx_album_picture_cover` (`album_id`, `is_cover`, `id`, `file_id`),
    CONSTRAINT `fk_album_picture_album` FOREIGN KEY (`album_id`) REFERENCES `album` (`album_id`) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `fk_album_picture_file` FOREIGN KEY (`file_id`) REFERENCES `file` (`file_id`) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `fk_album_picture_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `user_file` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `file_id` VARCHAR(36) NOT NULL,
    `is_deleted` TINYINT(1) NOT NULL DEFAULT 0,
    `upload_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `deleted_time` DATETIME DEFAULT NULL,
    `updated_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uq_user_file` (`user_id`, `file_id`),
    KEY `idx_user_upload` (`user_id`, `upload_time`),
    KEY `idx_file_status` (`file_id`, `is_deleted`),
    KEY `idx_user_file_user_file` (`user_id`, `file_id`),
    KEY `idx_user_file_user` (`user_id`),
    KEY `idx_user_file_user_file_deleted` (`user_id`, `file_id`, `is_deleted`),
    KEY `idx_user_file_user_deleted_file_upload` (`user_id`, `is_deleted`, `file_id`, `upload_time`),
    KEY `idx_user_file_file_user_deleted` (`file_id`, `user_id`, `is_deleted`),
    KEY `idx_user_file_deleted_time_file` (`is_deleted`, `deleted_time`, `file_id`, `user_id`),
    CONSTRAINT `fk_user_file_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_user_file_file` FOREIGN KEY (`file_id`) REFERENCES `file` (`file_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `record` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `operation_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `operation` VARCHAR(255) NOT NULL,
    `number` INT UNSIGNED NOT NULL DEFAULT 0,
    `ipv4` VARCHAR(45) NOT NULL,
    `user_id` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_time_operation` (`operation_time`),
    KEY `idx_userId` (`user_id`),
    KEY `idx_ipv4` (`ipv4`),
    CONSTRAINT `fk_record_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `picture_tag` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `file_id` VARCHAR(36) NOT NULL,
    `image_type` VARCHAR(100) DEFAULT NULL,
    `tag_name` VARCHAR(100) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_picture_tag_file_tag` (`file_id`, `tag_name`),
    KEY `idx_picture_tag_file_tag` (`file_id`, `tag_name`),
    KEY `idx_picture_tag_tag_file` (`tag_name`, `file_id`),
    KEY `idx_picture_tag_image_type_tag_file` (`image_type`, `tag_name`, `file_id`),
    CONSTRAINT `fk_picture_tag_file` FOREIGN KEY (`file_id`) REFERENCES `file` (`file_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `similar_picture` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `similar_id` VARCHAR(36) NOT NULL,
    `file_id` VARCHAR(36) NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `user_id` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    KEY `similar_picture_file_file_id_fk` (`file_id`),
    KEY `similar_picture_user_id_fk` (`user_id`),
    KEY `idx_similar_picture_user_file` (`user_id`, `file_id`, `similar_id`),
    CONSTRAINT `fk_similar_picture_file` FOREIGN KEY (`file_id`) REFERENCES `file` (`file_id`) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_similar_picture_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `face` (
    `face_id` BIGINT NOT NULL AUTO_INCREMENT,
    `file_id` VARCHAR(36) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `is_processed` TINYINT(1) NOT NULL DEFAULT 0,
    `feature_vector` LONGBLOB DEFAULT NULL,
    `feature_dim` INT DEFAULT NULL,
    `feature_model` VARCHAR(100) DEFAULT NULL,
    `feature_provider` VARCHAR(50) DEFAULT NULL,
    `detect_provider` VARCHAR(50) DEFAULT NULL,
    `bbox_json` TEXT DEFAULT NULL,
    `quality_score` DOUBLE DEFAULT NULL,
    `person_cover_url` VARCHAR(500) DEFAULT NULL,
    `person_object_name` VARCHAR(300) DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `is_face` TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (`face_id`),
    KEY `idx_file` (`file_id`),
    KEY `idx_face_user` (`user_id`),
    KEY `idx_face_model` (`user_id`, `feature_provider`, `feature_model`, `feature_dim`),
    KEY `idx_face_processed_id` (`is_processed`, `face_id`),
    KEY `idx_face_user_file` (`user_id`, `file_id`),
    KEY `idx_face_file_user` (`file_id`, `user_id`),
    CONSTRAINT `fk_face_file` FOREIGN KEY (`file_id`) REFERENCES `file` (`file_id`) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_face_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `person` (
    `person_id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `person_name` VARCHAR(255) DEFAULT NULL,
    `person_relation` VARCHAR(100) DEFAULT '无关系',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `display` TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (`person_id`),
    KEY `idx_name` (`person_name`),
    KEY `idx_person_user` (`user_id`),
    CONSTRAINT `fk_person_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `person_face` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `person_id` BIGINT NOT NULL,
    `face_id` BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `representative` TINYINT(1) NOT NULL DEFAULT 0,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `person_face_user_id_fk` (`user_id`),
    KEY `person_face_face_face_id_fk` (`face_id`),
    KEY `person_face_person_person_id_fk` (`person_id`),
    KEY `idx_person_face_user_person_rep_time` (`user_id`, `person_id`, `representative`, `update_time`, `face_id`),
    KEY `idx_person_face_face_user` (`face_id`, `user_id`, `person_id`),
    CONSTRAINT `fk_person_face_person` FOREIGN KEY (`person_id`) REFERENCES `person` (`person_id`) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_person_face_face` FOREIGN KEY (`face_id`) REFERENCES `face` (`face_id`) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_person_face_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `file_feature` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `file_id` VARCHAR(36) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `feature_vector` LONGBLOB NOT NULL,
    `feature_dim` INT DEFAULT NULL,
    `feature_model` VARCHAR(100) DEFAULT NULL,
    `feature_provider` VARCHAR(50) DEFAULT NULL,
    `feature_version` VARCHAR(50) DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_user_provider_model` (`file_id`, `user_id`, `feature_provider`, `feature_model`),
    KEY `idx_file_id` (`file_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_file_feature_user_file` (`user_id`, `file_id`),
    KEY `idx_file_feature_user` (`user_id`),
    KEY `idx_file_feature_model` (`user_id`, `feature_provider`, `feature_model`, `feature_dim`),
    KEY `idx_file_feature_user_model_file` (`user_id`, `feature_provider`, `feature_model`, `file_id`),
    KEY `idx_file_feature_file_user` (`file_id`, `user_id`),
    CONSTRAINT `fk_file_feature_file` FOREIGN KEY (`file_id`) REFERENCES `file` (`file_id`) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_file_feature_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `async_task` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `task_key` VARCHAR(190) NOT NULL,
    `task_type` VARCHAR(64) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `file_id` VARCHAR(64) DEFAULT NULL,
    `payload_json` TEXT DEFAULT NULL,
    `result_json` TEXT DEFAULT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    `retry_count` INT NOT NULL DEFAULT 0,
    `max_retries` INT NOT NULL DEFAULT 5,
    `next_retry_time` DATETIME DEFAULT NULL,
    `last_error` VARCHAR(1000) DEFAULT NULL,
    `started_at` DATETIME DEFAULT NULL,
    `completed_at` DATETIME DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_async_task_key` (`task_key`),
    KEY `idx_async_task_due` (`status`, `next_retry_time`),
    KEY `idx_async_task_type_status` (`task_type`, `status`),
    KEY `idx_async_task_user_time` (`user_id`, `create_time`),
    KEY `idx_async_task_status_retry_time` (`status`, `next_retry_time`, `create_time`, `id`),
    KEY `idx_async_task_type_file_key` (`task_type`, `file_id`, `task_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

CREATE TABLE IF NOT EXISTS `outbox_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `event_key` VARCHAR(128) NOT NULL,
    `event_type` VARCHAR(64) NOT NULL,
    `aggregate_type` VARCHAR(64) NOT NULL,
    `aggregate_id` VARCHAR(128) NOT NULL,
    `payload_json` JSON NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    `retry_count` INT NOT NULL DEFAULT 0,
    `max_retries` INT NOT NULL DEFAULT 10,
    `next_attempt_time` DATETIME DEFAULT NULL,
    `lock_token` VARCHAR(128) DEFAULT NULL,
    `locked_until` DATETIME DEFAULT NULL,
    `last_error` VARCHAR(1000) DEFAULT NULL,
    `published_at` DATETIME DEFAULT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_outbox_event_key` (`event_key`),
    KEY `idx_outbox_status_next_id` (`status`, `next_attempt_time`, `id`),
    KEY `idx_outbox_locked_until` (`status`, `locked_until`),
    KEY `idx_outbox_aggregate` (`aggregate_type`, `aggregate_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;

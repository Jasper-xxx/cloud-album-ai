CREATE TABLE IF NOT EXISTS `agent_image_tag_batch` (
    `batch_id` CHAR(36) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `pending_action_id` CHAR(36) NOT NULL,
    `min_confidence` DECIMAL(5,4) NOT NULL DEFAULT 0.5000,
    `total_count` INT NOT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `update_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`batch_id`),
    UNIQUE KEY `uk_agent_image_tag_batch_pending` (`pending_action_id`),
    KEY `idx_agent_image_tag_batch_user_time` (`user_id`, `create_time`),
    CONSTRAINT `fk_agent_image_tag_batch_user`
        FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `agent_image_tag_batch_item` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `batch_id` CHAR(36) NOT NULL,
    `file_id` VARCHAR(36) NOT NULL,
    `async_task_id` BIGINT NOT NULL,
    `create_time` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_image_tag_batch_file` (`batch_id`, `file_id`),
    KEY `idx_agent_image_tag_batch_task` (`async_task_id`, `batch_id`),
    CONSTRAINT `fk_agent_image_tag_item_batch`
        FOREIGN KEY (`batch_id`) REFERENCES `agent_image_tag_batch` (`batch_id`)
        ON DELETE CASCADE,
    CONSTRAINT `fk_agent_image_tag_item_file`
        FOREIGN KEY (`file_id`) REFERENCES `file` (`file_id`)
        ON DELETE CASCADE,
    CONSTRAINT `fk_agent_image_tag_item_task`
        FOREIGN KEY (`async_task_id`) REFERENCES `async_task` (`id`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

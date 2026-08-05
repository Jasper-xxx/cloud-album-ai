-- P1 Agent read-side indexes for combined search and library health analysis.
CREATE INDEX `idx_async_task_user_file_type_status_update`
    ON `async_task` (`user_id`, `file_id`, `task_type`, `status`, `update_time`);

CREATE INDEX `idx_similar_picture_user_group_file`
    ON `similar_picture` (`user_id`, `similar_id`, `file_id`);

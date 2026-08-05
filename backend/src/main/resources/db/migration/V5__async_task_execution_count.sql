ALTER TABLE async_task
    ADD COLUMN execution_count INT NOT NULL DEFAULT 0 AFTER status;

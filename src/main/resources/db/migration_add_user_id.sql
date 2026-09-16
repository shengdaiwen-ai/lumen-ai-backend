-- migration: 2026-09-08 加 user_id 关联
-- 执行方式: 在 lumen_ai 库里运行
ALTER TABLE sessions  ADD COLUMN user_id BIGINT NOT NULL AFTER id;
ALTER TABLE sessions  ADD INDEX idx_user_id (user_id);

ALTER TABLE messages  ADD COLUMN user_id BIGINT NOT NULL AFTER session_id;
ALTER TABLE messages  ADD INDEX idx_user_id (user_id);

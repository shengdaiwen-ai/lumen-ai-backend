-- ============================================================
-- schedules 表扩展：添加 user_id + 结构化频率字段
-- ============================================================

-- 若 schedules 已存在，先补 user_id
ALTER TABLE schedules
  ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 0 AFTER id,
  ADD COLUMN IF NOT EXISTS frequency VARCHAR(16) DEFAULT NULL COMMENT '每天/每周/每月/间隔触发',
  ADD COLUMN IF NOT EXISTS weekday INT DEFAULT NULL COMMENT '每周几 (1-7)',
  ADD COLUMN IF NOT EXISTS month_day INT DEFAULT NULL COMMENT '每月第几天 (1-31)',
  ADD COLUMN IF NOT EXISTS interval_value INT DEFAULT NULL COMMENT '间隔数值',
  ADD COLUMN IF NOT EXISTS interval_unit VARCHAR(16) DEFAULT NULL COMMENT '分钟/小时/天',
  ADD COLUMN IF NOT EXISTS run_hour INT DEFAULT NULL COMMENT '执行小时 (0-23)',
  ADD COLUMN IF NOT EXISTS run_minute INT DEFAULT NULL COMMENT '执行分钟 (0-59)',
  ADD INDEX IF NOT EXISTS idx_sched_user (user_id),
  ADD INDEX IF NOT EXISTS idx_sched_freq (frequency);

-- 若数据库全新初始化，直接重建（与 schema.sql 保持一致即可）

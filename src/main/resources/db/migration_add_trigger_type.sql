-- ============================================================
-- schedule_runs 表扩展：添加 trigger_type 字段区分手动/自动执行
-- ============================================================

ALTER TABLE schedule_runs
  ADD COLUMN IF NOT EXISTS trigger_type VARCHAR(16) NOT NULL DEFAULT 'manual' COMMENT 'manual(手动立即执行) / auto(定时自动执行)' AFTER status,
  ADD INDEX IF NOT EXISTS idx_trigger_type (trigger_type);

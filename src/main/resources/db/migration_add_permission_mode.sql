-- ============================================================
-- 迁移：users 表增加全局审批权限字段
-- 执行前请确认当前版本，直接运行即可
-- ============================================================

ALTER TABLE users
  ADD COLUMN permission_mode VARCHAR(16) NOT NULL DEFAULT 'smart'
  COMMENT '全局审批模式：manual(手动审批) / smart(自动审批) / fully(完全访问) / custom(自定义)'
  AFTER credits;

-- 如果已经存在则跳过（幂等）
-- ALTER TABLE users ADD COLUMN IF NOT EXISTS permission_mode VARCHAR(16) NOT NULL DEFAULT 'smart';

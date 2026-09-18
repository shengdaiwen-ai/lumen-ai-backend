-- ============================================================
-- 迁移：订阅升级（差额抵扣）+ 积分按月发放
-- 幂等：CREATE TABLE IF NOT EXISTS
-- 执行方式：DbMigrationRunner 在 subscription_credit_grants 不存在时自动执行一次；
--          也可手动执行（例如生产库单独跑）。
--
-- 说明：
--  1) 本表是「积分按月发放」的幂等账本。一次订阅 = 覆盖总月数份**月度权益包**，
--     每份一行：period_index 是第几期（1-based），period_start/period_end 是该期区间。
--     下单时预生成整段计划（年付 = 12 行，第 1 行立即发，其余 pending）。
--  2) 幂等基石是 uk_sub_period(subscription_id, period_index)：
--     惰性补发在「读积分」「登录」「下单」等多个入口都会跑，必须靠唯一键兜住重复入账。
--  3) 升级时旧订阅剩余 pending 的计划整体置 cancelled（不物理删除，便于追溯）；
--     未生效月的积分**从未发放**，所以升级退款不需要回收未来月的积分。
--  4) 只建表、不写种子数据 —— 发放计划只能由真实下单流程写入。
-- ============================================================

CREATE TABLE IF NOT EXISTS subscription_credit_grants (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  subscription_id BIGINT UNSIGNED NOT NULL COMMENT '归属订阅，对应 user_subscriptions.id',
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '关联 users.id（冗余，便于按用户批量补发）',
  period_index    INT             NOT NULL COMMENT '第几期（1-based）',
  period_start    DATETIME        NOT NULL COMMENT '该期开始时间；到点即可发放',
  period_end      DATETIME        NOT NULL COMMENT '该期结束时间',
  amount          DECIMAL(12,2)   NOT NULL DEFAULT 0.00 COMMENT '该期发放积分（下单时的套餐额度快照）',
  status          VARCHAR(16)     NOT NULL DEFAULT 'pending'
                  COMMENT 'pending(未发放) / granted(已发放) / cancelled(订阅升级终止，不再发放)',
  reward_id       BIGINT UNSIGNED NULL COMMENT '发放后关联的 credit_rewards.id',
  granted_at      DATETIME        NULL COMMENT '实际发放时间',
  created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_sub_period (subscription_id, period_index),
  INDEX idx_user_status_start (user_id, status, period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订阅积分发放计划（按月发放的幂等账本）';

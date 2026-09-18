-- ============================================================
-- 迁移：用户已购订阅 user_subscriptions
-- 幂等：CREATE TABLE IF NOT EXISTS
-- 执行方式：DbMigrationRunner 在 user_subscriptions 不存在时自动执行一次；
--          也可手动执行（例如生产库单独跑）。
--
-- 说明：本表记录「谁买了哪个套餐、按哪个周期、什么时候到期」，
--       是订阅弹框默认选中项的唯一数据源（不是 subscription_plans 里的 is_default）。
--       只建表、不写种子数据 —— 已购记录只能由真实的支付/开通流程写入，
--       不允许用假数据冒充用户已购买。
-- ============================================================

CREATE TABLE IF NOT EXISTS user_subscriptions (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT UNSIGNED NOT NULL COMMENT '关联 users.id',
  plan_code     VARCHAR(32)  NOT NULL COMMENT '套餐标识，对应 subscription_plans.code',
  billing_code  VARCHAR(32)  NOT NULL COMMENT '计费周期标识，对应 subscription_plan_billings.code',
  started_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生效时间',
  expires_at    DATETIME     NOT NULL COMMENT '到期时间；早于当前时间即视为已过期',
  status        VARCHAR(16)  NOT NULL DEFAULT 'active'
                COMMENT 'active(生效中) / expired(已过期) / cancelled(已取消) / upgraded(已升级替换)',
  net_paid_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00
                COMMENT '本行覆盖时长内的实际净支付总额（元）；升级抵扣折算的唯一基数，绝不使用套餐标价',
  total_months  INT          NOT NULL DEFAULT 1 COMMENT '覆盖总月数 n（续费累加），与 grants 计划行数一致',
  ended_at      DATETIME     NULL COMMENT '权益结束时间：升级/取消时写入（到期不必写，看 expires_at）',
  upgraded_to_plan_code VARCHAR(32) NULL COMMENT '升级去向套餐 code，便于追溯与对账',
  auto_renew    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '自动续费开关（二期使用，一期随下单写入）',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_user_status_expires (user_id, status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户已购订阅';

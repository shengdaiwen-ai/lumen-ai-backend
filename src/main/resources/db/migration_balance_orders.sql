-- ============================================================
-- 迁移：余额流水 balance_transactions + 订阅订单 subscription_orders
-- 幂等：CREATE TABLE IF NOT EXISTS
-- 执行方式：DbMigrationRunner 在 subscription_orders 不存在时自动执行一次；
--          也可手动执行（例如生产库单独跑）。
--
-- 说明：
--  1) subscription_orders 是「余额支付下单」的凭证 —— 每次支付生成一条订单，
--     记录下单时的套餐名/周期名/周期时长/金额快照，套餐后续改价不影响历史订单。
--  2) balance_transactions 是余额流水账 —— 规范 §10.3 要求金额变动必须留痕，
--     只改 users.balance 不留流水无法对账。本次只有「消费」一种类型，
--     充值/退款后续复用同一张表（type 已预留 recharge / refund）。
--  3) 两张表都只建表、不写种子数据 —— 订单与流水只能由真实支付动作产生，
--     塞演示数据等于伪造交易事实（同 migration_user_subscriptions.sql 的理由）。
-- ============================================================

CREATE TABLE IF NOT EXISTS subscription_orders (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  order_no      VARCHAR(40)   NOT NULL COMMENT '订单号（业务唯一）',
  user_id       BIGINT UNSIGNED NOT NULL COMMENT '关联 users.id',
  plan_code     VARCHAR(32)   NOT NULL COMMENT '套餐标识，对应 subscription_plans.code',
  billing_code  VARCHAR(32)   NOT NULL COMMENT '计费周期标识，对应 subscription_plan_billings.code',
  plan_name     VARCHAR(64)   NOT NULL COMMENT '下单时套餐名快照',
  billing_label VARCHAR(32)   NOT NULL COMMENT '下单时周期名快照，如 连续包月',
  period_months INT           NOT NULL DEFAULT 1 COMMENT '下单时周期时长快照（月），1=月付 12=年付',
  original_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '新套餐周期原价（抵扣前，元）',
  discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '本次升级抵扣额（元）；截断到不超过原价',
  amount        DECIMAL(12,2) NOT NULL COMMENT '实付金额（元），恒等于 original_amount - discount_amount',
  order_type    VARCHAR(16)   NOT NULL DEFAULT 'new'
                COMMENT 'new(新购) / renew(同档续费) / upgrade(升级替换，带抵扣)',
  source_subscription_id BIGINT UNSIGNED NULL COMMENT '抵扣来源订阅行（升级时为被替换的旧订阅）',
  pay_method    VARCHAR(16)   NOT NULL DEFAULT 'balance' COMMENT '支付方式：balance(余额支付)',
  status        VARCHAR(16)   NOT NULL DEFAULT 'pending' COMMENT 'pending(待支付) / paid(已支付) / cancelled(已取消)',
  paid_at       DATETIME      NULL     COMMENT '支付完成时间',
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_order_no (order_no),
  INDEX idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订阅支付订单';

CREATE TABLE IF NOT EXISTS balance_transactions (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id        BIGINT UNSIGNED NOT NULL COMMENT '关联 users.id',
  change_amount  DECIMAL(12,2) NOT NULL COMMENT '变动金额（元）：充值/退款为正，消费为负',
  balance_after  DECIMAL(12,2) NOT NULL COMMENT '变动后余额（元）',
  type           VARCHAR(16)   NOT NULL COMMENT 'recharge(充值) / consume(消费) / refund(退款)',
  ref_type       VARCHAR(32)   NULL     COMMENT '关联业务类型，如 subscription_order',
  ref_id         BIGINT UNSIGNED NULL   COMMENT '关联业务主键',
  remark         VARCHAR(255)  NULL     COMMENT '备注，如 购买旗舰版·连续包年',
  created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户余额流水';

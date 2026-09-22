-- ============================================================
-- 迁移：聊天积分消耗（两阶段扣费）三张表 + 初始数据
--   model_pricing       模型单价（积分/千token，运营可改，红线 14 禁硬编码）
--   credit_holds        预扣单（两阶段扣费的关联载体）
--   credit_group_ratio  分组倍率（plan / channel / default 三层查找）
-- 幂等：CREATE TABLE IF NOT EXISTS；种子数据带 NOT EXISTS 判重，重复执行不覆盖运营调整。
-- 列变更（credit_transactions 的 message_id/tokens/hold_id）由 DbMigrationRunner 逐列 ALTER。
-- ============================================================

CREATE TABLE IF NOT EXISTS model_pricing (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  model_code   VARCHAR(32)   NOT NULL COMMENT '模型标识，与 done 事件 / messages.model 一致',
  display_name VARCHAR(32)   NOT NULL COMMENT '展示名，如 Pro / Flash',
  input_price  DECIMAL(10,4) NOT NULL COMMENT '输入单价：积分/千token',
  output_price DECIMAL(10,4) NOT NULL COMMENT '输出单价：积分/千token',
  cache_ratio  DECIMAL(5,2)  NOT NULL DEFAULT 1.00
               COMMENT '缓存命中输入的折率 0~1（1=不折），DeepSeek 命中输入更便宜',
  is_free      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1 = 免费模型，消耗 0 积分',
  is_fallback  TINYINT(1)    NOT NULL DEFAULT 0
               COMMENT '1 = 未登记模型的兜底单价（计价不硬编码，兜底价也走表）',
  max_output   INT           NOT NULL DEFAULT 8192 COMMENT '预扣估算用的单次输出上限（token）',
  status       VARCHAR(16)   NOT NULL DEFAULT 'active' COMMENT 'active / disabled',
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_model (model_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型计价表（积分/千token）';

CREATE TABLE IF NOT EXISTS credit_holds (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  request_id      VARCHAR(64) NOT NULL COMMENT '幂等键：Web 聊天 = 每次请求 UUID，办公助理 = 消息 id 派生',
  user_id         BIGINT      NOT NULL COMMENT '关联 users.id',
  channel         VARCHAR(16) NOT NULL DEFAULT 'web' COMMENT 'web(Web 聊天) / ilink(办公助理)',
  conversation_id BIGINT      NULL     COMMENT 'Web 会话 id；办公助理通道无会话概念则为 NULL',
  model_code      VARCHAR(32) NOT NULL COMMENT '本次请求使用的模型',
  estimated_cost  DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '预扣额（积分，两位小数）',
  actual_cost     DECIMAL(12,2) NULL     COMMENT '结算后的实际消耗（积分，两位小数）',
  status          VARCHAR(16) NOT NULL DEFAULT 'held'
                  COMMENT 'held(已预扣) / settled(已结算) / refunded(已全额退款) / expired(僵尸单清扫退款)',
  created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  settled_at      DATETIME    NULL,
  UNIQUE KEY uk_request (request_id),
  KEY idx_status_created (status, created_at),
  KEY idx_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天积分预扣单（两阶段扣费的中间态，超时由惰性清扫退款）';

CREATE TABLE IF NOT EXISTS credit_group_ratio (
  id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  scope      VARCHAR(32)   NOT NULL COMMENT 'plan:<plan_code> / channel:<channel> / default',
  ratio      DECIMAL(5,2)  NOT NULL DEFAULT 1.00 COMMENT '倍率 0~10，作用于模型单价',
  remark     VARCHAR(64)   NULL COMMENT '备注（运营用）',
  created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_scope (scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='积分计价分组倍率（按档位/通道分别配倍率）';

-- ---------------------- 模型单价种子 ----------------------
-- 锚点：1 积分 = ¥0.01。pin = 上游输入价(¥/百万token) ÷ 1,000,000 × 100 = 上游价 / 10000。
-- 运营改价直接 UPDATE 本表，无需改代码重新发版。
INSERT INTO model_pricing (model_code, display_name, input_price, output_price, cache_ratio, is_free, is_fallback, max_output)
SELECT v.model_code, v.display_name, v.input_price, v.output_price, v.cache_ratio, 0, v.is_fallback, v.max_output
FROM (
            SELECT 'deepseek-v4-pro' AS model_code, 'Pro'   AS display_name, 0.2000 AS input_price, 0.8000 AS output_price, 0.25 AS cache_ratio, 1 AS is_fallback, 8192 AS max_output
  UNION ALL SELECT 'deepseek-flash',       'Flash',  0.1000, 0.4000, 0.25, 0, 8192
) v
WHERE NOT EXISTS (
  SELECT 1 FROM model_pricing p WHERE p.model_code = v.model_code
);

-- ---------------------- 倍率种子 ----------------------
-- default 兜底 1.0；各订阅档位建 1.0 行（运营改价只改本表，不动代码）
INSERT INTO credit_group_ratio (scope, ratio, remark)
SELECT 'default', 1.00, '默认倍率'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM credit_group_ratio r WHERE r.scope = 'default');

INSERT INTO credit_group_ratio (scope, ratio, remark)
SELECT CONCAT('plan:', p.code), 1.00, CONCAT(p.name, ' 计价倍率（默认与标准价一致）')
FROM subscription_plans p
WHERE NOT EXISTS (
  SELECT 1 FROM credit_group_ratio r WHERE r.scope = CONCAT('plan:', p.code)
);

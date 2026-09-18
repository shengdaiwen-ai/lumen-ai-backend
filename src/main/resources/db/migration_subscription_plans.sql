-- ============================================================
-- 迁移：订阅套餐（套餐 / 权益条目 / 计费周期）三张表 + 初始套餐数据
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE
-- 执行方式：DbMigrationRunner 在 subscription_plans 不存在时自动执行一次；
--          也可手动执行（例如生产库单独跑）。
-- ============================================================

CREATE TABLE IF NOT EXISTS subscription_plans (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code         VARCHAR(32)  NOT NULL COMMENT '套餐标识：standard / premium / flagship',
  name         VARCHAR(64)  NOT NULL COMMENT '套餐名，如 标准版',
  description  VARCHAR(255) NULL     COMMENT '一句话描述，如 4,000 积分，覆盖日常所需',
  monthly_credits INT       NOT NULL DEFAULT 0 COMMENT '每月发放积分；购买时按周期月数一次性发放',
  sort_order   INT          NOT NULL DEFAULT 0 COMMENT '展示顺序，升序',
  is_default   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否默认选中',
  status       VARCHAR(16)  NOT NULL DEFAULT 'active' COMMENT 'active(上架) / inactive(下架)',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_code (code),
  INDEX idx_status_sort (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订阅套餐';

CREATE TABLE IF NOT EXISTS subscription_plan_features (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  plan_id     BIGINT UNSIGNED NOT NULL COMMENT '关联 subscription_plans.id',
  icon        VARCHAR(32)  NOT NULL DEFAULT 'check' COMMENT '图标标识，前端映射：sparkles/calendar/globe/infinity/list',
  content     VARCHAR(255) NOT NULL COMMENT '权益文案',
  sort_order  INT          NOT NULL DEFAULT 0 COMMENT '展示顺序，升序',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_plan_content (plan_id, content),
  INDEX idx_plan_sort (plan_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订阅套餐权益条目';

CREATE TABLE IF NOT EXISTS subscription_plan_billings (
  id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  plan_id        BIGINT UNSIGNED NOT NULL COMMENT '关联 subscription_plans.id',
  code           VARCHAR(32)  NOT NULL COMMENT '计费周期标识：monthly-auto / monthly / yearly-auto / yearly',
  label          VARCHAR(32)  NOT NULL COMMENT '展示名，如 连续包月 / 1个月 / 连续包年 / 1年',
  discount_label VARCHAR(16)  NULL     COMMENT '折扣标签，如 7折；无折扣为 NULL',
  price          DECIMAL(10,2) NOT NULL COMMENT '价格（元）',
  unit           VARCHAR(8)   NOT NULL COMMENT '价格单位：/月 或 /年（展示用，勿当业务判断）',
  period_months  INT          NOT NULL DEFAULT 1 COMMENT '计费周期时长（月）：1=月付，12=年付。订阅到期时间 = 起始时间 + 本值',
  is_default     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否该套餐的默认计费周期',
  auto_renew     TINYINT(1)   NOT NULL DEFAULT 0
                 COMMENT '该周期是否具备自动续费资格（数据驱动，禁止解析 code 前缀判断）',
  sort_order     INT          NOT NULL DEFAULT 0 COMMENT '展示顺序，升序',
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_plan_code (plan_id, code),
  INDEX idx_plan_sort (plan_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订阅套餐计费周期';

-- ---------------------- 初始套餐数据 ----------------------

INSERT IGNORE INTO subscription_plans (code, name, description, monthly_credits, sort_order, is_default, status) VALUES
  ('standard', '标准版', '5,000 积分，覆盖日常所需',  5000,  1, 1, 'active'),
  ('premium',  '高级版', '9,000 积分，支持高频使用',  9000,  2, 0, 'active'),
  ('flagship', '旗舰版', '50,000 积分，满足重度需求', 50000, 3, 0, 'active');

INSERT IGNORE INTO subscription_plan_features (plan_id, icon, content, sort_order)
SELECT p.id, v.icon, v.content, v.sort_order
FROM (
            SELECT 'standard' AS plan_code, 'sparkles' AS icon, '每月基础2,000积分（有效期1个月）' AS content, 1 AS sort_order
  UNION ALL SELECT 'standard', 'calendar', '每月赠送3,000积分（有效期1个月）', 2
  UNION ALL SELECT 'standard', 'globe',    '全部模型可选',                       3
  UNION ALL SELECT 'standard', 'infinity', '无限次代码追踪补金',                 4
  UNION ALL SELECT 'standard', 'list',     '15个自动任务（限免99个）',           5
  UNION ALL SELECT 'premium',  'sparkles', '每月基础4,000积分（有效期1个月）',   1
  UNION ALL SELECT 'premium',  'calendar', '每月赠送5,000积分（有效期1个月）',   2
  UNION ALL SELECT 'premium',  'globe',    '全部模型可选',                       3
  UNION ALL SELECT 'premium',  'infinity', '无限次代码追踪补金',                 4
  UNION ALL SELECT 'premium',  'list',     '30个自动任务',                       5
  UNION ALL SELECT 'flagship', 'sparkles', '每月基础20,000积分（有效期1个月）',  1
  UNION ALL SELECT 'flagship', 'calendar', '每月赠送30,000积分（有效期1个月）',  2
  UNION ALL SELECT 'flagship', 'globe',    '全部模型可选',                       3
  UNION ALL SELECT 'flagship', 'infinity', '无限次代码追踪补金',                 4
  UNION ALL SELECT 'flagship', 'list',     '99个自动任务',                       5
) v
JOIN subscription_plans p ON p.code = v.plan_code;

INSERT IGNORE INTO subscription_plan_billings (plan_id, code, label, discount_label, price, unit, period_months, is_default, auto_renew, sort_order)
SELECT p.id, v.code, v.label, v.discount_label, v.price, v.unit, v.period_months, v.is_default, v.auto_renew, v.sort_order
FROM (
            SELECT 'standard' AS plan_code, 'monthly-auto' AS code, '连续包月' AS label, '7折'   AS discount_label,   70.00 AS price, '/月' AS unit,  1 AS period_months, 1 AS is_default, 1 AS auto_renew, 1 AS sort_order
  UNION ALL SELECT 'standard', 'monthly',     '1个月',   NULL,   99.00, '/月',  1, 0, 0, 2
  UNION ALL SELECT 'standard', 'yearly-auto', '连续包年', '5.6折', 672.00, '/年', 12, 0, 1, 3
  UNION ALL SELECT 'standard', 'yearly',      '1年',     '7折',   840.00, '/年', 12, 0, 0, 4
  UNION ALL SELECT 'premium',  'monthly-auto', '连续包月', '7折',  140.00, '/月',  1, 0, 1, 1
  UNION ALL SELECT 'premium',  'monthly',      '1个月',   NULL,   199.00, '/月',  1, 1, 0, 2
  UNION ALL SELECT 'premium',  'yearly-auto',  '连续包年', '5.6折', 1338.00, '/年', 12, 0, 1, 3
  UNION ALL SELECT 'premium',  'yearly',       '1年',     '7折',  1674.00, '/年', 12, 0, 0, 4
  UNION ALL SELECT 'flagship', 'monthly-auto', '连续包月', '7折',  700.00, '/月',  1, 0, 1, 1
  UNION ALL SELECT 'flagship', 'monthly',      '1个月',   NULL,   999.00, '/月',  1, 0, 0, 2
  UNION ALL SELECT 'flagship', 'yearly-auto',  '连续包年', '5.6折', 6716.00, '/年', 12, 1, 1, 3
  UNION ALL SELECT 'flagship', 'yearly',       '1年',     '7折',  8400.00, '/年', 12, 0, 0, 4
) v
JOIN subscription_plans p ON p.code = v.plan_code;

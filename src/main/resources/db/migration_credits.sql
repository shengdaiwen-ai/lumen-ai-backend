-- ============================================================
-- 迁移：积分账户 / 奖励积分 / 积分流水 三张表 + 初始数据
-- 幂等：CREATE TABLE IF NOT EXISTS；回填与种子数据均带 NOT EXISTS 判重，
--      重复执行不会重复插入，也不会覆盖运营调整过的数据。
-- 执行方式：DbMigrationRunner 在 credit_accounts 不存在时自动执行一次；
--          也可手动执行（例如生产库单独跑）。
-- ============================================================

CREATE TABLE IF NOT EXISTS credit_accounts (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT       NOT NULL COMMENT '关联 users.id',
  credit_type VARCHAR(16)  NOT NULL COMMENT 'general(通用积分，当前唯一在用)；预留扩展类型（如平台奖励积分）',
  product     VARCHAR(32)  NOT NULL DEFAULT 'Lumen AI' COMMENT '产品端',
  balance     DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '可用余额',
  sort_order  INT          NOT NULL DEFAULT 0 COMMENT '展示顺序，升序',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_type (user_id, credit_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户积分账户（按积分类型分账）';

CREATE TABLE IF NOT EXISTS credit_rewards (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT       NOT NULL COMMENT '关联 users.id',
  title       VARCHAR(64)  NOT NULL COMMENT '奖励名称，如 老用户福利 / 每日签到 / 每月登录赠送',
  credit_type VARCHAR(16)  NOT NULL COMMENT 'general / work',
  remaining   DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '剩余可用',
  total       DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '累计获得',
  expires_at  DATETIME     NULL     COMMENT '到期时间，NULL 表示无到期',
  batch_count INT          NOT NULL DEFAULT 1 COMMENT '批次数量，>1 时前端展示「共 N 笔」',
  subscription_id BIGINT UNSIGNED NULL COMMENT '归属订阅（订阅积分批次才有），对应 user_subscriptions.id',
  period_index INT         NULL COMMENT '归属期数（第几期），与 subscription_credit_grants.period_index 对应',
  sort_order  INT          NOT NULL DEFAULT 0 COMMENT '展示顺序，升序',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_user_sort (user_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='奖励积分批次（按来源聚合）';

CREATE TABLE IF NOT EXISTS credit_transactions (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT       NOT NULL COMMENT '关联 users.id',
  credit_type  VARCHAR(16)  NOT NULL DEFAULT 'general' COMMENT 'general(通用积分，当前唯一在用)；预留扩展类型（如平台奖励积分）',
  record_title VARCHAR(255) NOT NULL COMMENT '使用记录标题',
  model_name   VARCHAR(32)  NOT NULL DEFAULT 'auto' COMMENT '模型名称',
  product      VARCHAR(32)  NOT NULL DEFAULT 'Lumen AI' COMMENT '产品端',
  cost         DECIMAL(12,2) NOT NULL DEFAULT 0.00
               COMMENT '积分变动量：消耗为正、回收为负（符号与 type 一致，前端可直接展示）',
  type         VARCHAR(16)  NOT NULL DEFAULT 'consume'
               COMMENT 'consume(消耗) / reclaim(升级回收，负向) —— 类型与数值都留痕，便于对账',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user_time (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='积分消耗流水';

-- ---------------------- 账户回填 ----------------------
-- users.credits 已于 2026-09-17 删除（积分余额双源收敛到 credit_accounts 单一权威源）；
-- 老库的存量余额由 DbMigrationRunner#dropLegacyUserColumns 在删列前并入通用积分账户。
-- （新注册用户的账户由 CreditService 在首次访问时懒创建。）

-- ---------------------- 演示数据 ----------------------
-- ⚠️ 奖励批次与用量流水当前无真实产生路径（后端还没有「对话/任务完成扣积分」的逻辑），
--    这里写入的是**演示数据**，仅为把页面跑通；真实扣减逻辑落地后应清空这两张表重来。
--    用量记录的时间用 NOW() 相对偏移，保证「今天 / 7天 / 30天」筛选始终有区分度。

INSERT INTO credit_rewards (user_id, title, credit_type, remaining, total, expires_at, batch_count, sort_order)
SELECT u.id, v.title, v.credit_type, v.remaining, v.total, v.expires_at, v.batch_count, v.sort_order
FROM users u
CROSS JOIN (
            SELECT '老用户福利'   AS title, 'general' AS credit_type, 2700.00 AS remaining, 2700.00 AS total, CAST('2026-10-05 10:48:00' AS DATETIME) AS expires_at, 1 AS batch_count, 1 AS sort_order
  UNION ALL SELECT '每日签到',     'general', 300.00,   300.00, CAST(NULL AS DATETIME),                              2, 2
  UNION ALL SELECT '每月登录赠送', 'general',   0.00,   500.00, CAST('2026-09-30 23:59:00' AS DATETIME),          1, 3
) v
WHERE NOT EXISTS (
  SELECT 1 FROM credit_rewards r
  WHERE r.user_id = u.id AND r.title = v.title AND r.credit_type = v.credit_type
);

INSERT INTO credit_transactions (user_id, credit_type, record_title, model_name, product, cost, created_at)
SELECT u.id, 'general', v.record_title, 'auto', 'Lumen AI', v.cost,
       DATE_SUB(NOW(), INTERVAL v.hours_ago HOUR)
FROM users u
CROSS JOIN (
            SELECT 'TRAE 移动端去除'             AS record_title, 2.34 AS cost,   2 AS hours_ago
  UNION ALL SELECT '优化设置弹窗样式及其功能',       9.37,   5
  UNION ALL SELECT '你确定你改了吗',                5.76,  26
  UNION ALL SELECT '不，如图所示，做成这样的效果',   1.81,  30
  UNION ALL SELECT '改好聊天框样式，灰色条连到一起', 3.12,  34
  UNION ALL SELECT '侧边栏宽度再加大一些',           2.56,  60
  UNION ALL SELECT '点击放大缩小不要显示分辨率数值', 4.23,  74
  UNION ALL SELECT '导航栏和侧边栏颜色统一',         1.98,  96
  UNION ALL SELECT '聊天框做成大卡片样式',           2.87, 110
  UNION ALL SELECT '侧边栏菜单收缩功能',             3.45, 200
  UNION ALL SELECT '全局文字颜色加深',               1.56, 260
  UNION ALL SELECT '用户弹框主题子菜单',             2.01, 340
  UNION ALL SELECT '设置面板完全重写',               8.64, 480
  UNION ALL SELECT '订阅套餐弹框对接服务端',         1.30, 600
) v
WHERE NOT EXISTS (
  SELECT 1 FROM credit_transactions t
  WHERE t.user_id = u.id AND t.record_title = v.record_title
);

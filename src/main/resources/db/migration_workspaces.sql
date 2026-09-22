-- 工作空间（选择工作空间需求）
--
-- ⚠️ 服务端**不存路径**：绝对路径只在客户端本地（ai-coding-settings.workspacePaths）。
--    这里是元数据表 —— 隐私（路径含用户名与目录结构）、云端无法校验路径有效性、
--    跨设备语义天然隔离，三条都要求路径不进服务端。
--
-- ⚠️ 建表三件套必须写全（红线 8/8b）：只写 DEFAULT CHARSET=utf8mb4 时表排序规则取
--    「该字符集的默认值」（MySQL 8 = utf8mb4_0900_ai_ci），与库默认的 utf8mb4_unicode_ci
--    并存 → 跨表 JOIN 随时 "Illegal mix of collations"。

CREATE TABLE IF NOT EXISTS workspaces (
  id              BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '归属用户',
  device_id       VARCHAR(64)   NOT NULL COMMENT '客户端安装期生成的设备标识（哪台机器上的空间）',
  name            VARCHAR(64)   NOT NULL COMMENT '展示名，默认取目录名',
  -- 无 permission_mode 列：权限档位是账号级全局设置（users.permission_mode），不按空间覆盖
  last_used_at    DATETIME      NULL     COMMENT '最近使用时间，列表按它倒序',
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_device_name (user_id, device_id, name),
  INDEX idx_user_device_recent (user_id, device_id, last_used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作空间元数据（不含路径）';

-- 套餐配额（机器可读数值）
--
-- 为什么必须单开一张表：subscription_plan_features 是**展示文案**（icon + 中文句子），
-- 拿它做判断等于解析自然语言；而「空间上限」是个必须可运营、可调的数字（红线 14）。
-- 做成 key-value 后，后续「自动任务个数」等配额可直接复用同一张表。
CREATE TABLE IF NOT EXISTS subscription_plan_quotas (
  id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  plan_code   VARCHAR(32) NOT NULL COMMENT '套餐 code；free = 未订阅时的默认档',
  quota_key   VARCHAR(32) NOT NULL COMMENT '配额键：workspace_limit / automation_limit ...',
  quota_value INT         NOT NULL COMMENT '配额数值；-1 = 不限',
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_plan_quota (plan_code, quota_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='套餐配额（机器可读数值）';

-- 只在缺失时插入：运营改过数值后重跑迁移不会覆盖（INSERT IGNORE 靠唯一键判重）
INSERT IGNORE INTO subscription_plan_quotas (plan_code, quota_key, quota_value) VALUES
  ('free',     'workspace_limit',  3),
  ('standard', 'workspace_limit',  5),
  ('premium',  'workspace_limit', 20),
  ('flagship', 'workspace_limit', 99);

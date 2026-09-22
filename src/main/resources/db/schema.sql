-- ============================================================
-- Lumen AI Backend - Database Schema（新建库基线，**非启动脚本**）
-- Version: 0.2.0
-- Engine: MySQL 8.0+
-- ------------------------------------------------------------
-- ⚠️ 本文件不参与启动迁移：DbMigrationRunner 只执行 db/migration_*.sql。
-- 它的用途是「从零建库时的基线定义」与人工查阅；新增表/列时请**同时**更新本文件
-- 与对应的 migration 脚本，否则两者会漂移（本文件历史上就漂过一次）。
--
-- 2026-09-22 清理：删除 memories / tool_calls / plugins / mcp_servers 四张从未上线的
-- 脚手架表，以及 sessions.project_path（已被 sessions.workspace_id 取代的裸路径列，红线 18）。
-- ============================================================

CREATE DATABASE IF NOT EXISTS lumen_ai
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE lumen_ai;

-- --------------------------- users ---------------------------
DROP TABLE IF EXISTS users;
CREATE TABLE users (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  email        VARCHAR(128) NOT NULL UNIQUE,
  username     VARCHAR(64)  NOT NULL,
  password     VARCHAR(255) NOT NULL COMMENT 'BCrypt hash',
  balance      DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '账户余额（元），可用于购买套餐',
  avatar_url   VARCHAR(500) DEFAULT NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  permission_mode VARCHAR(16) NOT NULL DEFAULT 'default' COMMENT 'default(默认权限) / full(完全访问权限)',
  INDEX idx_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- --------------------------- sessions ---------------------------
DROP TABLE IF EXISTS sessions;
CREATE TABLE sessions (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT        NOT NULL,
  title        VARCHAR(255)  NOT NULL DEFAULT '新会话',
  workspace_id BIGINT UNSIGNED DEFAULT NULL COMMENT '绑定的工作空间 id（权威引用；创建会话时锁定）',
  mode         VARCHAR(32)   NOT NULL DEFAULT 'default' COMMENT 'default / plan / specify',
  status       VARCHAR(32)   NOT NULL DEFAULT 'active'   COMMENT 'active / pinned / archived',
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_status (status),
  INDEX idx_created (created_at),
  INDEX idx_workspace (workspace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话表';

-- --------------------------- messages ---------------------------
DROP TABLE IF EXISTS messages;
CREATE TABLE messages (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id   BIGINT        NOT NULL,
  role         VARCHAR(16)   NOT NULL COMMENT 'user / assistant / tool / system',
  content      MEDIUMTEXT    DEFAULT NULL,
  tool_calls   JSON          DEFAULT NULL,
  tool_result  MEDIUMTEXT    DEFAULT NULL,
  attachments  JSON          DEFAULT NULL,
  model        VARCHAR(64)   DEFAULT NULL,
  input_tokens INT           DEFAULT 0,
  output_tokens INT          DEFAULT 0,
  latency_ms   INT           DEFAULT 0,
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_session (session_id),
  INDEX idx_role (role),
  CONSTRAINT fk_msg_session FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息表';

-- --------------------------- schedules ---------------------------
DROP TABLE IF EXISTS schedules;
CREATE TABLE schedules (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id         BIGINT        NOT NULL,
  workspace_id    BIGINT UNSIGNED DEFAULT NULL COMMENT '绑定的工作空间 id（NULL = 无空间，本地类工具不下发）',
  name            VARCHAR(128)  NOT NULL,
  frequency       VARCHAR(16)   DEFAULT NULL COMMENT '每天/每周/每月/间隔触发',
  weekday         VARCHAR(32)   DEFAULT NULL COMMENT '每周几，逗号分隔如 1,3,5',
  month_day       INT           DEFAULT NULL COMMENT '每月第几天 (1-31)',
  interval_value  INT           DEFAULT NULL COMMENT '间隔数值',
  interval_unit   VARCHAR(16)   DEFAULT NULL COMMENT '分钟/小时/天',
  run_hour        INT           DEFAULT NULL COMMENT '执行小时 (0-23)',
  run_minute      INT           DEFAULT NULL COMMENT '执行分钟 (0-59)',
  cron            VARCHAR(64)   DEFAULT NULL,
  message         MEDIUMTEXT    DEFAULT NULL,
  timezone        VARCHAR(64)   NOT NULL DEFAULT 'Asia/Shanghai',
  status          VARCHAR(16)   NOT NULL DEFAULT 'active' COMMENT 'active / paused',
  last_run_at     DATETIME      DEFAULT NULL,
  next_run_at     DATETIME      DEFAULT NULL,
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_sched_user (user_id),
  INDEX idx_status (status),
  INDEX idx_sched_freq (frequency),
  INDEX idx_sched_workspace (workspace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定时任务表';

-- --------------------------- schedule_runs ---------------------------
DROP TABLE IF EXISTS schedule_runs;
CREATE TABLE schedule_runs (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  schedule_id  BIGINT        NOT NULL,
  user_id      BIGINT        NOT NULL,
  status       VARCHAR(16)   NOT NULL COMMENT 'success / failed / running',
  trigger_type VARCHAR(16)   NOT NULL DEFAULT 'manual' COMMENT 'manual(手动立即执行) / auto(定时自动执行)',
  session_id   BIGINT        DEFAULT NULL,
  result       TEXT          DEFAULT NULL,
  error        TEXT          DEFAULT NULL,
  started_at   DATETIME      NOT NULL,
  ended_at     DATETIME      DEFAULT NULL,
  INDEX idx_schedule (schedule_id),
  INDEX idx_user (user_id),
  INDEX idx_status (status),
  INDEX idx_trigger_type (trigger_type),
  CONSTRAINT fk_runs_schedule FOREIGN KEY (schedule_id) REFERENCES schedules(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定时任务执行历史表';

-- --------------------------- workspaces ---------------------------
-- 🔴 只存元数据，**没有路径列**（红线 18）：本机绝对路径只在客户端。
-- （原 permission_mode 列已废弃 —— 权限档位改为全局生效，不再按空间覆盖。）
DROP TABLE IF EXISTS workspaces;
CREATE TABLE workspaces (
  id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT UNSIGNED NOT NULL COMMENT '归属用户',
  device_id    VARCHAR(64)   NOT NULL COMMENT '客户端安装期生成的设备标识（哪台机器上的空间）',
  name         VARCHAR(64)   NOT NULL COMMENT '展示名，默认取目录名',
  last_used_at DATETIME      DEFAULT NULL COMMENT '最近使用时间，列表按它倒序',
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_user_device_name (user_id, device_id, name),
  INDEX idx_user_device (user_id, device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作空间元数据表（不含路径）';

-- --------------------------- permissions（已删除） ---------------------------
-- 原 permissions 表存「工具级三态」（auto_allow / ask_user / deny），是旧审批模型的残留：
--   (1) 与权限档位（users.permission_mode）职责重叠 —— 同一个决定有两处真源；
--   (2) 工具的危险性本就写在工具自己身上（Tool#requiredPermission()），放库里必然
--       「改了库、代码里的工具声明没改」。
-- 2026-09-22 重构后由「2 档权限 + 工具自身声明 + 客户端沙箱策略」决定，该表已删除
-- （存量库由 DbMigrationRunner#deprecatedCleanup 启动时 DROP）。

-- --------------------------- audit_logs ---------------------------
-- 审计中心（P4，2026-09-22）：客户端沙箱的拦截/放行/审批决定上报落库。
-- 判定在客户端（执行下沉），服务端只存不判；target_digest 只存摘要
-- （SHA-256 前 16 位 hex），原文（命令/路径/URL）不进服务端（红线 18）。
-- 与迁移脚本 db/migration_audit_logs.sql 保持同步。
CREATE TABLE audit_logs (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT UNSIGNED NOT NULL COMMENT '归属用户（上报者 JWT 主体，不信任请求体）',
  device_id     VARCHAR(64)   NULL COMMENT '产生该事件的设备标识',
  session_id    BIGINT UNSIGNED NULL COMMENT '关联会话（可空）',
  workspace_id  BIGINT UNSIGNED NULL COMMENT '关联工作空间元数据 id（不含路径）',
  type          VARCHAR(16)   NOT NULL COMMENT 'file / command / network(预留)',
  tool_name     VARCHAR(32)   NOT NULL COMMENT '工具名',
  target_digest VARCHAR(64)   NOT NULL COMMENT '目标摘要，不存原文',
  decision      VARCHAR(16)   NOT NULL COMMENT 'allow / deny',
  reason        VARCHAR(512)  NULL COMMENT '判定原因（不含路径原文）',
  isolation     VARCHAR(16)   NOT NULL DEFAULT 'policy-only' COMMENT 'P5 隔离层级：os-fence / policy-only',
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_audit_user_time (user_id, created_at),
  INDEX idx_audit_user_type (user_id, type),
  INDEX idx_audit_user_decision (user_id, decision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计中心：沙箱拦截/放行/审批决定（目标只存摘要）';

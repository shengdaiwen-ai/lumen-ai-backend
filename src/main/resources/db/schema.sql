-- ============================================================
-- Lumen AI Backend - Database Schema
-- Version: 0.1.0
-- Engine: MySQL 8.0+
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
  plan         VARCHAR(16)  NOT NULL DEFAULT 'free' COMMENT 'free / pro',
  credits      INT          NOT NULL DEFAULT 0,
  avatar_url   VARCHAR(500) DEFAULT NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_email (email)
) ENGINE=InnoDB COMMENT='用户表';

-- --------------------------- sessions ---------------------------
DROP TABLE IF EXISTS sessions;
CREATE TABLE sessions (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  title        VARCHAR(255)  NOT NULL DEFAULT '新会话',
  project_path VARCHAR(500)  DEFAULT NULL,
  mode         VARCHAR(32)   NOT NULL DEFAULT 'default' COMMENT 'default / plan / specify',
  status       VARCHAR(32)   NOT NULL DEFAULT 'active'   COMMENT 'active / archived',
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_status (status),
  INDEX idx_created (created_at)
) ENGINE=InnoDB COMMENT='会话表';

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
) ENGINE=InnoDB COMMENT='消息表';

-- --------------------------- tool_calls ---------------------------
DROP TABLE IF EXISTS tool_calls;
CREATE TABLE tool_calls (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_id   BIGINT        NOT NULL,
  tool_name    VARCHAR(64)   NOT NULL,
  args         JSON          DEFAULT NULL,
  output       MEDIUMTEXT    DEFAULT NULL,
  status       VARCHAR(16)   NOT NULL COMMENT 'success / failed / cancelled',
  duration_ms  INT           DEFAULT 0,
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_message (message_id),
  INDEX idx_tool_name (tool_name),
  INDEX idx_status (status),
  CONSTRAINT fk_tc_message FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='工具调用表';

-- --------------------------- memories ---------------------------
DROP TABLE IF EXISTS memories;
CREATE TABLE memories (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  scope        VARCHAR(16)   NOT NULL COMMENT 'user / project / session',
  scope_id     VARCHAR(64)   DEFAULT NULL,
  content      TEXT          NOT NULL,
  keywords     VARCHAR(500)  DEFAULT NULL,
  ttl_days     INT           DEFAULT 30,
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_scope (scope, scope_id),
  INDEX idx_ttl (ttl_days)
) ENGINE=InnoDB COMMENT='记忆表';

-- --------------------------- plugins ---------------------------
DROP TABLE IF EXISTS plugins;
CREATE TABLE plugins (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  name         VARCHAR(64)   NOT NULL,
  version      VARCHAR(32)   NOT NULL,
  enabled      TINYINT(1)    NOT NULL DEFAULT 1,
  config       JSON          DEFAULT NULL,
  installed_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_name (name),
  INDEX idx_enabled (enabled)
) ENGINE=InnoDB COMMENT='插件表';

-- --------------------------- mcp_servers ---------------------------
DROP TABLE IF EXISTS mcp_servers;
CREATE TABLE mcp_servers (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  name         VARCHAR(64)   NOT NULL,
  command      VARCHAR(500)  DEFAULT NULL,
  args         JSON          DEFAULT NULL,
  env          JSON          DEFAULT NULL,
  status       VARCHAR(16)   NOT NULL DEFAULT 'offline' COMMENT 'online / offline / error',
  auth_config  JSON          DEFAULT NULL,
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_status (status)
) ENGINE=InnoDB COMMENT='MCP 服务器表';

-- --------------------------- schedules ---------------------------
DROP TABLE IF EXISTS schedules;
CREATE TABLE schedules (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id         BIGINT        NOT NULL,
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
  INDEX idx_sched_freq (frequency)
) ENGINE=InnoDB COMMENT='定时任务表';

-- --------------------------- schedule_runs ---------------------------
DROP TABLE IF EXISTS schedule_runs;
CREATE TABLE schedule_runs (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  schedule_id  BIGINT        NOT NULL,
  user_id      BIGINT        NOT NULL,
  status       VARCHAR(16)   NOT NULL COMMENT 'success / failed / running',
  result       TEXT          DEFAULT NULL,
  error        TEXT          DEFAULT NULL,
  started_at   DATETIME      NOT NULL,
  ended_at     DATETIME      DEFAULT NULL,
  INDEX idx_schedule (schedule_id),
  INDEX idx_user (user_id),
  INDEX idx_status (status),
  CONSTRAINT fk_runs_schedule FOREIGN KEY (schedule_id) REFERENCES schedules(id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='定时任务执行历史表';

-- --------------------------- permissions ---------------------------
DROP TABLE IF EXISTS permissions;
CREATE TABLE permissions (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  tool_name    VARCHAR(64)   NOT NULL UNIQUE,
  default_mode VARCHAR(32)   NOT NULL DEFAULT 'ask_user' COMMENT 'auto_allow=安全操作,隔离环境下自动放行 | ask_user=重要操作,手动审批需用户确认,自动审批AI自动批准 | deny=高风险操作,隔离环境下直接阻止',
  updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='工具权限配置表';

-- 初始化内置工具权限
INSERT INTO permissions (tool_name, default_mode) VALUES
  ('Read',         'auto_allow'),
  ('Glob',         'auto_allow'),
  ('Grep',         'auto_allow'),
  ('LS',           'auto_allow'),
  ('Write',        'ask_user'),
  ('Edit',         'ask_user'),
  ('Delete',       'ask_user'),
  ('Shell',        'ask_user'),
  ('Exec',         'ask_user'),
  ('WebSearch',    'ask_user'),
  ('WebFetch',     'ask_user'),
  ('Agent',        'ask_user'),
  ('AskUserQuestion', 'auto_allow'),
  ('DateTime',     'auto_allow'),
  ('ScheduleTool', 'ask_user');

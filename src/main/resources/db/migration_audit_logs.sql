-- 审计中心（工作空间/沙箱重构 P4，方案 §3.1）
--
-- 记录客户端沙箱的每次「拦截 / 放行 / 审批决定」。判定发生在客户端（执行已下沉，
-- 服务端不知道路径），所以本表的数据由客户端通过 /audit/events 上报落库。
--
-- 🔴 红线 18 同源约束：target_digest 存**摘要**（SHA-256 前 16 位 hex）而非原文 ——
--    命令与 URL 里常含敏感串，本机绝对路径更不得进入服务端（连列名都叫 digest，
--    防止后来者把原文塞进来当「明细」用）。
--
-- ⚠️ 建表三件套必须写全（红线 8/8b）：漏 COLLATE 会被 ensureUtf8mb4Collation 兜底，
--    但显式声明是规范要求 —— 跨表 JOIN 字符列（如与 users）时排序规则不一致会直接报错。

CREATE TABLE IF NOT EXISTS audit_logs (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT UNSIGNED NOT NULL COMMENT '归属用户（上报者的 JWT 主体，不信任请求体）',
  device_id     VARCHAR(64)   NULL COMMENT '产生该事件的设备标识（可空：旧客户端未带）',
  session_id    BIGINT UNSIGNED NULL COMMENT '关联会话（可空：部分决策不挂会话）',
  workspace_id  BIGINT UNSIGNED NULL COMMENT '关联工作空间元数据 id（不含路径）',
  type          VARCHAR(16)   NOT NULL COMMENT 'file(文件操作) / command(命令执行) / network(网络目标，预留)',
  tool_name     VARCHAR(32)   NOT NULL COMMENT '工具名：Read/Write/Edit/Delete/Glob/Grep/Shell/Exec',
  target_digest VARCHAR(64)   NOT NULL COMMENT '目标摘要（SHA-256 前 16 位 hex），不存原文（红线 18）',
  decision      VARCHAR(16)   NOT NULL COMMENT 'allow(放行) / deny(拦截或用户拒绝)',
  reason        VARCHAR(512)  NULL COMMENT '判定原因（规则命中 / 档位兜底文案，不含路径原文）',
  isolation     VARCHAR(16)   NOT NULL DEFAULT 'policy-only' COMMENT 'P5 隔离层级：os-fence(OS 围栏) / policy-only(仅策略层)',
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_audit_user_time (user_id, created_at),
  INDEX idx_audit_user_type (user_id, type),
  INDEX idx_audit_user_decision (user_id, decision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计中心：沙箱拦截/放行/审批决定（目标只存摘要）';

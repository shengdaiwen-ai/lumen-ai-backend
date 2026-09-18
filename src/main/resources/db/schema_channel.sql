-- ========== 办公助理（iLink Bot Channel）==========
-- 跨通讯工具（微信/飞书）的 AI 消息通道
-- iLink 是腾讯官方微信 Bot 协议，Electron 主进程直连 iLink API

CREATE TABLE IF NOT EXISTS channel_accounts (
  id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT UNSIGNED NOT NULL COMMENT '关联 user.id',
  channel          VARCHAR(32)  NOT NULL COMMENT '通道类型: weixin / feishu',
  account_id       VARCHAR(128) NULL     COMMENT '通道侧账号 ID（iLink bot_id）',
  account_name     VARCHAR(128) NULL     COMMENT '通道侧账号昵称',
  bind_status      VARCHAR(16)  NOT NULL DEFAULT 'unbound' COMMENT 'unbound / binding / bound / offline',
  bot_token        TEXT         NULL     COMMENT 'iLink bot_token（Bearer）',
  ilink_bot_id     VARCHAR(128) NULL     COMMENT 'iLink 返回的 bot_id',
  ilink_user_id    VARCHAR(128) NULL     COMMENT 'iLink 返回的 user_id',
  base_url         VARCHAR(256) NULL     COMMENT 'iLink baseurl（登录后返回）',
  qr_code          TEXT         NULL     COMMENT '最近一次二维码内容',
  qr_img_content   VARCHAR(512) NULL     COMMENT '二维码 URL（liteapp.weixin.qq.com 链接）',
  qr_expires_at    DATETIME     NULL     COMMENT '二维码过期时间',
  last_poll_at     DATETIME     NULL     COMMENT '最后一次收到消息时间',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_user (user_id),
  INDEX idx_channel (channel),
  UNIQUE KEY uk_user_channel (user_id, channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='办公助理通道账号绑定';

CREATE TABLE IF NOT EXISTS channel_messages (
  id               BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT UNSIGNED NOT NULL COMMENT '关联 user.id',
  account_id       BIGINT UNSIGNED NOT NULL COMMENT '关联 channel_accounts.id',
  channel          VARCHAR(32)  NOT NULL COMMENT 'weixin / feishu',
  peer_id          VARCHAR(128) NOT NULL COMMENT '对方 ID（个人 open_id 或群 chat_id）',
  peer_name        VARCHAR(128) NULL     COMMENT '对方昵称',
  direction        VARCHAR(8)   NOT NULL COMMENT 'inbound（手机→服务）/ outbound（服务→手机）',
  message_type     VARCHAR(16)  NOT NULL DEFAULT 'text' COMMENT 'text / image / file / typing',
  content          TEXT         NULL     COMMENT '消息内容（text）或 JSON 元数据（image/file）',
  media_url        VARCHAR(512) NULL     COMMENT '图片/文件下载 URL（可选）',
  ilink_msg_id     VARCHAR(128) NULL     COMMENT 'iLink 侧消息 ID（去重用）',
  context_token    VARCHAR(256) NULL     COMMENT 'iLink context_token（回复用）',
  is_read          TINYINT(1)   NOT NULL DEFAULT 0,
  agent_executed   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否已由 Agent 处理并回复',
  input_tokens     INT          NULL     COMMENT 'AI 回复消耗的输入 token',
  output_tokens    INT          NULL     COMMENT 'AI 回复消耗的输出 token',
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user (user_id),
  INDEX idx_account (account_id),
  INDEX idx_peer (peer_id),
  INDEX idx_created (created_at),
  INDEX idx_direction (direction)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='办公助理通道消息';

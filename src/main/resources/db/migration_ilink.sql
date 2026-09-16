-- iLink Bot 架构迁移：为 channel_accounts / channel_messages 增加新字段
-- 幂等执行：字段已存在则跳过

-- === channel_accounts ===
ALTER TABLE channel_accounts
  ADD COLUMN IF NOT EXISTS bot_token TEXT NULL COMMENT 'iLink bot_token（Bearer）' AFTER bind_status,
  ADD COLUMN IF NOT EXISTS ilink_bot_id VARCHAR(128) NULL COMMENT 'iLink 返回的 bot_id' AFTER bot_token,
  ADD COLUMN IF NOT EXISTS ilink_user_id VARCHAR(128) NULL COMMENT 'iLink 返回的 user_id' AFTER ilink_bot_id,
  ADD COLUMN IF NOT EXISTS base_url VARCHAR(256) NULL COMMENT 'iLink baseurl' AFTER ilink_user_id,
  ADD COLUMN IF NOT EXISTS qr_img_content VARCHAR(512) NULL COMMENT '二维码 URL（liteapp.weixin.qq.com/q/...）' AFTER qr_code;

-- 旧字段保留，先不删（可能还有其他引用）
-- openclaw_token  → bot_token（数据迁移可后续做）

-- === channel_messages ===
ALTER TABLE channel_messages
  ADD COLUMN IF NOT EXISTS ilink_msg_id VARCHAR(128) NULL COMMENT 'iLink 侧消息 ID（去重用）' AFTER media_url,
  ADD COLUMN IF NOT EXISTS context_token VARCHAR(256) NULL COMMENT 'iLink context_token（回复用）' AFTER ilink_msg_id;

-- 旧字段 openclaw_msg_id 保留

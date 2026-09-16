-- iLink Bot 架构迁移
-- 注意：MySQL ALTER ADD COLUMN 不支持 IF NOT EXISTS，
-- 所以用 continue-on-error=true 让字段已存在时跳过错误

-- === channel_accounts ===
ALTER TABLE channel_accounts ADD COLUMN bot_token TEXT NULL COMMENT 'iLink bot_token';
ALTER TABLE channel_accounts ADD COLUMN ilink_bot_id VARCHAR(128) NULL COMMENT 'iLink bot_id';
ALTER TABLE channel_accounts ADD COLUMN ilink_user_id VARCHAR(128) NULL COMMENT 'iLink user_id';
ALTER TABLE channel_accounts ADD COLUMN base_url VARCHAR(256) NULL COMMENT 'iLink baseurl';
ALTER TABLE channel_accounts ADD COLUMN qr_img_content VARCHAR(512) NULL COMMENT '二维码URL';

-- === channel_messages ===
ALTER TABLE channel_messages ADD COLUMN ilink_msg_id VARCHAR(128) NULL COMMENT 'iLink消息ID';
ALTER TABLE channel_messages ADD COLUMN context_token VARCHAR(256) NULL COMMENT 'iLink context_token';

-- 通道消息 token 用量迁移：记录 AI 回复消耗的 token，供聊天页展示
-- 说明：MySQL 不支持 ADD COLUMN IF NOT EXISTS，若字段已存在会报 Duplicate column，可忽略

ALTER TABLE channel_messages
  ADD COLUMN input_tokens  INT NULL COMMENT 'AI 回复消耗的输入 token' AFTER agent_executed,
  ADD COLUMN output_tokens INT NULL COMMENT 'AI 回复消耗的输出 token' AFTER input_tokens;

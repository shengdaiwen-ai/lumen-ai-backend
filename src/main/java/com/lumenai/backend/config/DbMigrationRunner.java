package com.lumenai.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时执行数据库结构迁移（幂等）
 * <p>
 * 当前迁移：为 schedule_runs 表添加 trigger_type 字段，区分手动/自动执行。
 */
@Slf4j
@Component
@Order(1)
public class DbMigrationRunner implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    public DbMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        addTriggerTypeColumn();
        addSessionIdColumn();
        addMessagesCancelledColumn();
        // iLink Bot 架构迁移
        addChannelAccountsIlinkColumns();
        addChannelMessagesIlinkColumns();
    }

    private void addTriggerTypeColumn() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'schedule_runs' AND COLUMN_NAME = 'trigger_type'",
                    Integer.class
            );
            if (count != null && count > 0) {
                log.debug("schedule_runs.trigger_type 已存在，跳过迁移");
                return;
            }
            jdbcTemplate.execute(
                    "ALTER TABLE schedule_runs " +
                            "ADD COLUMN trigger_type VARCHAR(16) NOT NULL DEFAULT 'manual' COMMENT 'manual(手动立即执行) / auto(定时自动执行)' AFTER status"
            );
            jdbcTemplate.execute(
                    "ALTER TABLE schedule_runs ADD INDEX idx_trigger_type (trigger_type)"
            );
            log.info("迁移完成：schedule_runs.trigger_type 字段已添加");
        } catch (Exception e) {
            log.warn("schedule_runs.trigger_type 迁移失败（可能已存在）: {}", e.getMessage());
        }
    }

    private void addSessionIdColumn() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'schedule_runs' AND COLUMN_NAME = 'session_id'",
                    Integer.class
            );
            if (count != null && count > 0) {
                log.debug("schedule_runs.session_id 已存在，跳过迁移");
                return;
            }
            jdbcTemplate.execute(
                    "ALTER TABLE schedule_runs " +
                            "ADD COLUMN session_id BIGINT NULL DEFAULT NULL COMMENT '关联会话ID' AFTER user_id"
            );
            jdbcTemplate.execute(
                    "ALTER TABLE schedule_runs ADD INDEX idx_session_id (session_id)"
            );
            log.info("迁移完成：schedule_runs.session_id 字段已添加");
        } catch (Exception e) {
            log.warn("schedule_runs.session_id 迁移失败（可能已存在）: {}", e.getMessage());
        }
    }

    private void addMessagesCancelledColumn() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'messages' AND COLUMN_NAME = 'cancelled'",
                    Integer.class
            );
            if (count != null && count > 0) {
                log.debug("messages.cancelled 已存在，跳过迁移");
                return;
            }
            jdbcTemplate.execute(
                    "ALTER TABLE messages ADD COLUMN cancelled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否被用户手动终止'"
            );
            log.info("迁移完成：messages.cancelled 字段已添加");
        } catch (Exception e) {
            log.warn("messages.cancelled 迁移失败（可能已存在）: {}", e.getMessage());
        }
    }

    private void addChannelAccountsIlinkColumns() {
        addColumnIfNotExists("channel_accounts", "bot_token",
                "TEXT NULL COMMENT 'iLink bot_token（Bearer）'", "bind_status");
        addColumnIfNotExists("channel_accounts", "ilink_bot_id",
                "VARCHAR(128) NULL COMMENT 'iLink 返回的 bot_id'", "bot_token");
        addColumnIfNotExists("channel_accounts", "ilink_user_id",
                "VARCHAR(128) NULL COMMENT 'iLink 返回的 user_id'", "ilink_bot_id");
        addColumnIfNotExists("channel_accounts", "base_url",
                "VARCHAR(256) NULL COMMENT 'iLink baseurl'", "ilink_user_id");
        addColumnIfNotExists("channel_accounts", "qr_img_content",
                "VARCHAR(512) NULL COMMENT '二维码URL（liteapp.weixin.qq.com/q/...）'", "qr_code");
    }

    private void addChannelMessagesIlinkColumns() {
        addColumnIfNotExists("channel_messages", "ilink_msg_id",
                "VARCHAR(128) NULL COMMENT 'iLink 侧消息 ID（去重用）'", "media_url");
        addColumnIfNotExists("channel_messages", "context_token",
                "VARCHAR(256) NULL COMMENT 'iLink context_token（回复用）'", "ilink_msg_id");
    }

    private void addColumnIfNotExists(String table, String column, String definition, String afterColumn) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column
            );
            if (count != null && count > 0) {
                log.debug("{}.{} 已存在，跳过", table, column);
                return;
            }
            jdbcTemplate.execute(String.format(
                    "ALTER TABLE %s ADD COLUMN %s %s AFTER %s", table, column, definition, afterColumn
            ));
            log.info("迁移完成：{}.{} 字段已添加", table, column);
        } catch (Exception e) {
            log.warn("{}.{} 迁移失败: {}", table, column, e.getMessage());
        }
    }
}

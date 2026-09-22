package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户表 users
 *
 * <p><b>已移除两个历史遗留列</b>（2026-09-17）：
 * {@code credits} —— 积分余额的权威源是 {@code credit_accounts}（见 CreditService）；
 * {@code plan} —— 套餐的事实源是 {@code user_subscriptions}，本列当年只在注册时写过一次
 * "free"，买套餐后从不更新，长期与现实矛盾（库里已有 standard 生效订阅的用户仍显示 "free"）。</p>
 */
@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String email;

    private String username;

    /** BCrypt hash */
    private String password;

    /** 账户余额（元），可用于购买套餐 */
    private BigDecimal balance;

    /**
     * 全局权限档位（2 档，2026-09-22 起）：{@code default}（默认权限）/ {@code full}（完全访问权限）。
     * <p>旧四档 manual / smart / fully / custom 已废弃，存量值由
     * {@code DbMigrationRunner#permissionModeTwoTier} 启动时迁移。执行侧判据见
     * {@code AgentEngine#getPermissionMode}：只有 {@code full} 才放行，其余一律按 {@code default}。</p>
     */
    @TableField("permission_mode")
    private String permissionMode;

    @TableField("avatar_url")
    private String avatarUrl;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

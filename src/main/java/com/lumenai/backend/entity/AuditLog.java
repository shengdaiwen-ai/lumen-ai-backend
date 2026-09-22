package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志 audit_logs（工作空间/沙箱重构 P4）。
 *
 * <p>数据来源：客户端沙箱的每次「拦截 / 放行 / 审批决定」经 {@code POST /audit/events}
 * 上报落库。判定发生在客户端（工具执行已下沉），服务端只做归属与存储。</p>
 *
 * <p>🔴 {@code targetDigest} 只存摘要（SHA-256 前 16 位 hex）—— 命令与路径可能含
 * 敏感串，原文进服务端属隐私泄露（红线 18）。</p>
 */
@Data
@TableName("audit_logs")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("device_id")
    private String deviceId;

    @TableField("session_id")
    private Long sessionId;

    @TableField("workspace_id")
    private Long workspaceId;

    /** 事件类型：file(文件操作) / command(命令执行) / network(网络目标，预留) */
    private String type;

    @TableField("tool_name")
    private String toolName;

    /** 目标摘要（SHA-256 前 16 位 hex），不存原文 */
    @TableField("target_digest")
    private String targetDigest;

    /** 决定：allow(放行) / deny(拦截或用户拒绝) */
    private String decision;

    /** 判定原因（规则命中 / 档位兜底文案，不含路径原文） */
    private String reason;

    /** P5 隔离层级：os-fence(OS 围栏) / policy-only(仅策略层)。红线 19：如实记录，不谎报隔离 */
    private String isolation;

    private LocalDateTime createdAt;
}

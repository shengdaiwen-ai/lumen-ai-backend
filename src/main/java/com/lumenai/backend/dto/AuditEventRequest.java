package com.lumenai.backend.dto;

import lombok.Data;

/**
 * 客户端审计事件上报体（POST /audit/events）。
 *
 * <p>🔴 服务端不信任请求体里的用户身份 —— userId 一律取 JWT 主体；
 * device_id / session_id / workspace_id 由客户端填写，服务端只校验归属（workspace 归属
 * 交由服务端验：不属于本人即整条拒绝，防止伪造他人空间的审计记录）。</p>
 *
 * <p>🔴 {@code targetDigest} 必须是摘要（客户端已哈希），服务端不接收任何路径/命令原文。</p>
 */
@Data
public class AuditEventRequest {

    /** 事件类型：file / command / network */
    private String type;

    private String toolName;

    /** 目标摘要（SHA-256 前 16 位 hex），不存原文 */
    private String targetDigest;

    /** allow / deny */
    private String decision;

    /** 判定原因（不含路径原文） */
    private String reason;

    /** P5 隔离层级：os-fence / policy-only（可空，缺省按 policy-only 落库） */
    private String isolation;

    private Long sessionId;

    private Long workspaceId;
}

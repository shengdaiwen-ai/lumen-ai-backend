package com.lumenai.backend.dto;

import lombok.Data;

@Data
public class SessionCreateRequest {
    private String title;
    /**
     * 绑定的工作空间 id；不传 = 无空间会话（文件与命令类工具不下发）。
     * <p>服务端会校验该空间属于当前用户且在当前设备上 —— 不可用（跨设备 / 已移除 / 悬空 id）
     * 一律 404 {@code WORKSPACE_NOT_FOUND}，**不静默降级成无空间会话**。只有请求压根没带
     * {@code workspaceId} 时才建无空间会话。</p>
     */
    private Long workspaceId;
    private String mode;
}

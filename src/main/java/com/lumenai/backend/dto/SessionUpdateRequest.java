package com.lumenai.backend.dto;

import lombok.Data;

@Data
public class SessionUpdateRequest {
    private String title;
    private String status;
    /**
     * 工作空间回填（仅「迁移历史工作目录」用）。
     *
     * <p>服务端只接受<b>单向</b>赋值：会话当前未绑定时可绑，已绑定时传不同的值直接拒绝
     * （{@code SESSION_WORKSPACE_LOCKED}）—— 会话创建后不可更换工作空间（FR-12）。</p>
     */
    private Long workspaceId;
}

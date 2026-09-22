package com.lumenai.backend.dto;

import lombok.Data;

/**
 * 更新工作空间：重命名。
 *
 * <p>只改服务端元数据 —— <b>绝不重命名磁盘目录</b>（那个目录属于用户，改完名相对路径全失效）。</p>
 *
 * <p>原先还带 {@code permissionMode}（空间级覆盖账号档位），已随 2026-09-22 重构删除：
 * 权限档位改为账号级全局生效。</p>
 */
@Data
public class WorkspaceUpdateRequest {

    /** 新展示名；null = 不改 */
    private String name;
}

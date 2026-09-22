package com.lumenai.backend.dto;

import com.lumenai.backend.entity.Workspace;

import java.time.LocalDateTime;

/**
 * 工作空间视图对象。
 *
 * <p>⚠️ <b>不含路径</b> —— 服务端根本没有路径（§5.1）。前端拿 {@code id} 去本地映射表
 * 取绝对路径；取不到就是「该工作空间在本机不可用」（跨设备场景）。</p>
 *
 * <p>也不含权限档位：档位是账号级全局设置，从 {@code /profile} 读，不挂在空间上。</p>
 */
public class WorkspaceVO {

    private Long id;
    private String name;
    /** 归属设备；与本机 deviceId 不一致即「本机不可用」 */
    private String deviceId;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static WorkspaceVO from(Workspace w) {
        WorkspaceVO vo = new WorkspaceVO();
        vo.id = w.getId();
        vo.name = w.getName();
        vo.deviceId = w.getDeviceId();
        vo.lastUsedAt = w.getLastUsedAt();
        vo.createdAt = w.getCreatedAt();
        vo.updatedAt = w.getUpdatedAt();
        return vo;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

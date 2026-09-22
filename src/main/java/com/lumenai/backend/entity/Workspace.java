package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作空间元数据（**不含路径**）。
 *
 * <p>产品部署在云服务器上，服务端拿到的绝对路径毫无意义（既无法校验有效性，也不该存隐私），
 * 因此本表只有「谁在哪台机器上有个叫什么的项目」。绝对路径由客户端本地按 {@code workspaceId}
 * 映射（见需求文档 §5.4）。</p>
 *
 * <p><b>没有权限档位列</b>：档位是账号级的全局设置（{@code users.permission_mode}），
 * 不再按空间覆盖 —— 原 {@code workspaces.permission_mode} 列已随 2026-09-22 重构删除。</p>
 */
@Data
@TableName("workspaces")
public class Workspace {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    /** 客户端安装期生成的设备标识（哪台机器上的空间） */
    @TableField("device_id")
    private String deviceId;

    private String name;

    @TableField("last_used_at")
    private LocalDateTime lastUsedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

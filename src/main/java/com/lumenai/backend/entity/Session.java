package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sessions")
public class Session {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    private String title;

    /**
     * 绑定的工作空间 id（权威引用）。
     *
     * <p>{@code null} = 无空间会话：纯聊天，读/写/编辑/删除/Glob/Grep/Shell/Exec 一律不下发
     * （见需求文档 §6.3）。<b>创建会话时锁定，会话期不可更换</b> —— 中途换目录会让相对路径
     * 与已读写的文件全部失锚。</p>
     */
    @TableField("workspace_id")
    private Long workspaceId;

    private String mode;

    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

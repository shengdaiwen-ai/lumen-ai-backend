package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表 users
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

    /** free / pro */
    private String plan;

    private Integer credits;

    /** 全局审批模式：manual / smart / fully / custom */
    @TableField("permission_mode")
    private String permissionMode;

    @TableField("avatar_url")
    private String avatarUrl;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

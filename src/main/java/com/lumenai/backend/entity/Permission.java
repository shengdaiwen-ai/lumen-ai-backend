package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("permissions")
public class Permission {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("tool_name")
    private String toolName;

    @TableField("default_mode")
    private String defaultMode; // auto_allow / ask_user / deny

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

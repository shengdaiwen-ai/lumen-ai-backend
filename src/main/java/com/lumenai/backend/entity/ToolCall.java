package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用表 tool_calls
 */
@Data
@TableName("tool_calls")
public class ToolCall {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("message_id")
    private Long messageId;

    @TableField("tool_name")
    private String toolName;

    private String args;

    private String output;

    /** status: success / failed / cancelled */
    private String status;

    @TableField("duration_ms")
    private Integer durationMs;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

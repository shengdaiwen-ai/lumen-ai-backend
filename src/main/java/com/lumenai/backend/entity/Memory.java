package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 记忆表 memories
 */
@Data
@TableName("memories")
public class Memory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** scope: user / project / session */
    private String scope;

    @TableField("scope_id")
    private String scopeId;

    private String content;

    private String keywords;

    @TableField("ttl_days")
    private Integer ttlDays;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

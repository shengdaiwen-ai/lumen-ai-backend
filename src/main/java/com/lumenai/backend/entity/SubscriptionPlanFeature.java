package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订阅套餐权益条目 subscription_plan_features
 */
@Data
@TableName("subscription_plan_features")
public class SubscriptionPlanFeature {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 subscription_plans.id */
    @TableField("plan_id")
    private Long planId;

    /** 图标标识，前端映射：sparkles/calendar/globe/infinity/list */
    private String icon;

    /** 权益文案 */
    private String content;

    /** 展示顺序，升序 */
    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

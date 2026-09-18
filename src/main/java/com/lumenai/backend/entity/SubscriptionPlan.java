package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订阅套餐 subscription_plans
 */
@Data
@TableName("subscription_plans")
public class SubscriptionPlan {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 套餐标识：standard / premium / flagship */
    private String code;

    /** 套餐名，如 标准版 */
    private String name;

    /** 一句话描述 */
    private String description;

    /** 每月发放积分；购买时按周期月数一次性发放（见 SubscriptionOrderService） */
    @TableField("monthly_credits")
    private Integer monthlyCredits;

    /** 展示顺序，升序 */
    @TableField("sort_order")
    private Integer sortOrder;

    /** 是否默认选中 */
    @TableField("is_default")
    private Boolean isDefault;

    /** active(上架) / inactive(下架) */
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 套餐配额（机器可读数值）。
 *
 * <p>与 {@link SubscriptionPlanFeature} 的区别：那张表存的是给人看的权益文案，
 * 这张表存的是给代码判断的数字（{@code workspace_limit} 等）。可运营、可改、可加键。</p>
 */
@Data
@TableName("subscription_plan_quotas")
public class SubscriptionPlanQuota {

    /** 工作空间数量上限 */
    public static final String KEY_WORKSPACE_LIMIT = "workspace_limit";

    /** 未订阅时的默认档位 code（复用 {@code SubscriptionService#currentActivePlan} 返回 null 的语义） */
    public static final String PLAN_FREE = "free";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("plan_code")
    private String planCode;

    @TableField("quota_key")
    private String quotaKey;

    /** 配额数值；{@code -1} = 不限 */
    @TableField("quota_value")
    private Integer quotaValue;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订阅套餐计费周期 subscription_plan_billings
 */
@Data
@TableName("subscription_plan_billings")
public class SubscriptionPlanBilling {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 subscription_plans.id */
    @TableField("plan_id")
    private Long planId;

    /** 计费周期标识：monthly-auto / monthly / yearly-auto / yearly */
    private String code;

    /** 展示名，如 连续包月 */
    private String label;

    /** 折扣标签，如 7折；无折扣为 null */
    @TableField("discount_label")
    private String discountLabel;

    /** 价格（元） */
    private BigDecimal price;

    /** 价格单位：/月 或 /年（展示用，勿当业务判断） */
    private String unit;

    /** 计费周期时长（月）：1=月付，12=年付。订阅到期时间 = 起始时间 + 本值 */
    @TableField("period_months")
    private Integer periodMonths;

    /** 是否该套餐的默认计费周期 */
    @TableField("is_default")
    private Boolean isDefault;

    /**
     * 该周期是否具备自动续费资格（数据驱动）。
     * <b>禁止在代码里解析 {@code code} 前缀</b>（如 {@code endsWith("-auto")}）判断能否续费 ——
     * 那是把命名约定当业务规则，运营改一次 code 就静默失效。二期自动续费据此判定。
     */
    @TableField("auto_renew")
    private Boolean autoRenew;

    /** 展示顺序，升序 */
    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

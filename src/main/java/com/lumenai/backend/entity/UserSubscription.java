package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户已购订阅 user_subscriptions
 *
 * <p>套餐默认选中项的数据源：用户「买了且没过期」时按本表回显其套餐与计费周期，
 * 否则回落到 {@code subscription_plans.is_default} 标记的默认套餐。</p>
 */
@Data
@TableName("user_subscriptions")
public class UserSubscription {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    /** 套餐标识，对应 subscription_plans.code */
    @TableField("plan_code")
    private String planCode;

    /** 计费周期标识，对应 subscription_plan_billings.code */
    @TableField("billing_code")
    private String billingCode;

    @TableField("started_at")
    private LocalDateTime startedAt;

    /** 到期时间；早于当前时间即视为已过期 */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** active / expired / cancelled / upgraded(已升级替换) */
    private String status;

    /**
     * 本行覆盖时长内的<b>实际净支付总额</b>（元），升级抵扣折算的唯一基数。
     *
     * <p>用净支付额而不是套餐标价：标价可以被反复套利 —— 拿「标价算出的剩余价值」去抵新套餐价，
     * 等于平台额外补贴。续费时累加（混合周期购买后月均价自然成为加权平均）。</p>
     */
    @TableField("net_paid_amount")
    private BigDecimal netPaidAmount;

    /** 覆盖总月数 n（续费累加），与 subscription_credit_grants 的计划行数一致 */
    @TableField("total_months")
    private Integer totalMonths;

    /** 权益结束时间：升级/取消时写入（自然到期看 expires_at 即可） */
    @TableField("ended_at")
    private LocalDateTime endedAt;

    /** 升级去向套餐 code（保留追溯线索，不物理删除旧行） */
    @TableField("upgraded_to_plan_code")
    private String upgradedToPlanCode;

    /** 自动续费开关（二期使用，一期随下单写入） */
    @TableField("auto_renew")
    private Boolean autoRenew;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订阅支付订单 subscription_orders
 *
 * <p>余额支付下单的凭证。套餐名 / 周期名 / 周期时长 / 金额都是<b>下单时的快照</b>，
 * 之后运营改价或改名都不影响历史订单的可读性与对账。</p>
 */
@Data
@TableName("subscription_orders")
public class SubscriptionOrder {

    /** 待支付 */
    public static final String STATUS_PENDING = "pending";

    /** 已支付 */
    public static final String STATUS_PAID = "paid";

    /** 已取消 */
    public static final String STATUS_CANCELLED = "cancelled";

    /** 余额支付 */
    public static final String PAY_METHOD_BALANCE = "balance";

    /** 新购（无抵扣） */
    public static final String TYPE_NEW = "new";

    /** 同档续费（顺延，无抵扣） */
    public static final String TYPE_RENEW = "renew";

    /** 升级替换（带抵扣） */
    public static final String TYPE_UPGRADE = "upgrade";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号（业务唯一） */
    @TableField("order_no")
    private String orderNo;

    @TableField("user_id")
    private Long userId;

    /** 套餐标识，对应 subscription_plans.code */
    @TableField("plan_code")
    private String planCode;

    /** 计费周期标识，对应 subscription_plan_billings.code */
    @TableField("billing_code")
    private String billingCode;

    /** 下单时套餐名快照 */
    @TableField("plan_name")
    private String planName;

    /** 下单时周期名快照，如 连续包月 */
    @TableField("billing_label")
    private String billingLabel;

    /** 下单时周期时长快照（月） */
    @TableField("period_months")
    private Integer periodMonths;

    /** 新套餐周期原价（抵扣前，元）。与 amount 一起构成完整账目：收多少、抵多少、实付多少 */
    @TableField("original_amount")
    private BigDecimal originalAmount;

    /** 本次升级抵扣额（元）；已截断到不超过原价（超出部分作废，不结转） */
    @TableField("discount_amount")
    private BigDecimal discountAmount;

    /**
     * 实付金额（元），<b>恒等于 {@code original_amount − discount_amount}</b>。
     * 语义与改造前一致（都是「用户实际付出的钱」），只是现在可能小于原价。
     */
    private BigDecimal amount;

    /** new(新购) / renew(同档续费) / upgrade(升级替换) */
    @TableField("order_type")
    private String orderType;

    /** 抵扣来源订阅行（升级时为被替换的旧订阅），便于对账 */
    @TableField("source_subscription_id")
    private Long sourceSubscriptionId;

    /** 支付方式：balance */
    @TableField("pay_method")
    private String payMethod;

    /** pending / paid / cancelled */
    private String status;

    @TableField("paid_at")
    private LocalDateTime paidAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

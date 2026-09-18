package com.lumenai.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 余额支付下单结果（POST /subscription/orders 出参）
 *
 * <p>前端据此更新余额（{@code balanceAfter}）、展示开通结果（{@code expiresAt}），
 * 并刷新套餐列表（已购套餐会带上 owned 标记）。</p>
 */
@Data
@Builder
public class SubscriptionOrderVO {

    /** 订单号 */
    private String orderNo;

    /** 套餐标识 */
    private String planCode;

    /** 套餐名（下单时快照） */
    private String planName;

    /** 计费周期标识 */
    private String billingCode;

    /** 周期名（下单时快照），如 连续包月 */
    private String billingLabel;

    /** 周期时长（月） */
    private Integer periodMonths;

    /** 实付金额（元）。升级时 = 原价 − 抵扣额；新购/续费时 = 原价 */
    private BigDecimal amount;

    /** 订单类型：new(新购) / renew(同档续费) / upgrade(升级替换) */
    private String orderType;

    /** 新套餐周期原价（元，抵扣前） */
    private BigDecimal originalAmount;

    /** 本次升级抵扣额（元）；无抵扣为 0 */
    private BigDecimal discountAmount;

    /** 因周期缩短而作废的抵扣额（元）；> 0 表示用户是在二次确认后继续的 */
    private BigDecimal wastedDiscount;

    /** 升级时回收的旧套餐积分（按与折算相同的比例收回）；未升级或无需回收为 0 */
    private BigDecimal reclaimedCredits;

    /** 未生效的整月数（年付升级时用于提示「另有 N 个月已全额退还」） */
    private Integer unusedMonths;

    /** 实际采用的折算比例 k = min(当月剩余时间比例, 当月剩余积分比例) */
    private BigDecimal ratioApplied;

    /** 支付方式：balance */
    private String payMethod;

    /** 订单状态：paid */
    private String status;

    /** 支付时间（yyyy-MM-dd HH:mm） */
    private String paidAt;

    /** 本次购买后的订阅到期时间（yyyy-MM-dd） */
    private String expiresAt;

    /** 扣款后余额（元），前端直接回写，避免再发一次 /auth/me */
    private BigDecimal balanceAfter;

    /** 本次开通赠送的积分（= 套餐每月积分 × 周期月数） */
    private BigDecimal grantedCredits;

    /** 赠送后通用积分余额 */
    private BigDecimal creditsAfter;
}

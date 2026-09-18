package com.lumenai.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 升级折算预览（GET /subscription/preview 出参）
 *
 * <p>只读接口，供订阅弹框在用户切换套餐/周期时展示「省了多少、为什么是这个数」。
 * <b>所有金额、天数、比例都在服务端算好下发</b>，前端只做格式化 ——
 * 前端按 {@code billingCode} 或 {@code sortOrder} 自行推算折扣是明令禁止的：
 * 那等于把计价权交给客户端，且会与下单口径悄悄漂移。</p>
 *
 * <p>点「支付」时服务端会<b>用同一份算法重算</b>（见 {@code SubscriptionUpgradeService}），
 * 因此本接口被篡改也不影响真实成交金额。</p>
 */
@Data
@Builder
public class SubscriptionPreviewVO {

    /** 目标套餐 code */
    private String planCode;

    /** 目标套餐名（用于汇总区第一行） */
    private String planName;

    /** 目标计费周期 code */
    private String billingCode;

    /** 目标计费周期名，如 连续包月 */
    private String billingLabel;

    /** 新套餐周期原价（抵扣前） */
    private BigDecimal originalAmount;

    /** 未生效月份全额退还的部分（{@code m × F}），展示「另有 N 个月尚未开始，已全额退还 ¥X」用 */
    private BigDecimal unusedAmount;

    /** 抵扣额（已截断到不超过原价） */
    private BigDecimal discountAmount;

    /** 应付（实付） */
    private BigDecimal payAmount;

    /** 因周期缩短而作废的抵扣额；> 0 时前端必须弹二次确认层 */
    private BigDecimal wastedDiscount;

    /** 升级时应回收的旧套餐积分（= 当月批次额度 × k）；未升级为 0 */
    private BigDecimal reclaimCredits;

    /** new(新购) / renew(同档续费) / upgrade(升级替换) */
    private String orderType;

    /** 当前生效套餐 code，无订阅为 null */
    private String ownedPlanCode;

    /** 当前生效套餐名（展示「标准版剩余价值抵扣」用） */
    private String ownedPlanName;

    /** 当前订阅剩余天数（展示「剩余 13 天」用），无订阅为 null */
    private Integer remainingDays;

    /** 旧订阅覆盖总月数 n */
    private Integer ownedMonths;

    /** 当前所处期号 i */
    private Integer currentPeriodIndex;

    /** 未生效整月数 F（展示「另有 N 个月尚未开始，已全额退还」用） */
    private Integer unusedMonths;

    /** 当月剩余时间比例 t */
    private BigDecimal ratioTime;

    /** 当月剩余积分比例 u */
    private BigDecimal ratioCredit;

    /** 实际采用的比例 k = min(t, u) */
    private BigDecimal ratioApplied;

    /**
     * 抵扣为 0 的原因，供前端给出「为什么没抵扣 + 替代路径」：
     * {@code no_subscription} / {@code renewal} / {@code credits_exhausted} /
     * {@code period_nearly_over} / {@code no_paid_amount}；null 表示不适用。
     */
    private String noDiscountReason;

    /** 本次购买后的到期时间（yyyy-MM-dd） */
    private String newExpiresAt;

    /** 升级前的到期时间（yyyy-MM-dd），无订阅为 null */
    private String previousExpiresAt;

    /** 新到期日是否早于原到期日（周期缩短） */
    private Boolean expiresShrunk;

    /** 提前的天数 */
    private Integer shrinkDays;

    /** 余额是否够付本次应付 */
    private Boolean balanceEnough;

    /**
     * 本次支付后<b>立即到账</b>的积分（按月发放的第 1 期额度）。
     * 续费为 0 —— 续费是往后顺延，要等新周期开始才发，否则用户会在重叠期拿到两份。
     */
    private Integer grantedCredits;

    /** 当前可用余额（元） */
    private BigDecimal balance;
}

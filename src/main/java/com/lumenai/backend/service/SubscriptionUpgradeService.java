package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumenai.backend.common.exception.BusinessException;
import com.lumenai.backend.entity.CreditReward;
import com.lumenai.backend.entity.SubscriptionOrder;
import com.lumenai.backend.entity.SubscriptionPlan;
import com.lumenai.backend.entity.SubscriptionPlanBilling;
import com.lumenai.backend.entity.UserSubscription;
import com.lumenai.backend.mapper.CreditRewardMapper;
import com.lumenai.backend.mapper.SubscriptionPlanMapper;
import com.lumenai.backend.mapper.UserSubscriptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 升级折算服务 —— 旧套餐「剩余价值」的计算口径
 *
 * <p>核心模型：一次订阅 = {@code n} 份<b>月度权益包</b>（每份 = 一个月的服务 + 一份积分），
 * 折算即回答「哪几份已经交付、哪几份没有」：</p>
 *
 * <pre>
 * n = 覆盖总月数        P = 净支付额 net_paid_amount      m = P / n（月均价）
 * i = 当前月序号        F = n − i（未生效整月数）
 * t = 当月剩余时间比例  u = 当月批次 remaining / total
 * k = min(t, u)                                        ← 取小
 * 抵扣 G = m × F + m × k       应付 A = max(新价 − G, 0)
 * </pre>
 *
 * <p><b>三段结算规则</b>：</p>
 * <ol>
 *   <li>已过完的月份（1 ~ i−1）<b>不退</b> —— 权益已完整交付，否则用户用满 11 个月再升级还要退钱；</li>
 *   <li>当月按 {@code m × min(t, u)} 退 —— 时间与积分两个维度里用户<b>真正还剩</b>的那部分才折钱，
 *       两侧归零条件都成立（积分耗尽 → 0；时间走完 → 0）；</li>
 *   <li>未生效月份（i+1 ~ n）全额退 {@code m × F} —— 时长未到、积分未发，两样都没交付。</li>
 * </ol>
 *
 * <p><b>为什么当月要取 min(t, u) 而不是只看积分</b>：只看积分的话，
 * 「9 月 1 日买月付、9 月 28 日升级、期间没怎么用 AI」会算出全额抵扣 → 用户白用 28 天。</p>
 *
 * <p><b>为什么基数用净支付额而不用套餐标价</b>：标价作基数可被反复套利 ——
 * 拿「按标价算出的剩余价值」去抵新套餐价，等于平台额外补贴。</p>
 *
 * <p><b>关键性质：无套利</b>。刚买当天升级（{@code k = 1}）时累计支出恰好等于直接买新套餐的价格，
 * 用户无法靠「先买低档再立刻升级」省钱；之后每一元的超出都精确对应他真实消耗掉的旧套餐权益。</p>
 *
 * <p>同一份 {@link Quote} 同时供只读预览接口与下单事务使用 —— <b>口径只有一处实现</b>，
 * 前端算的只是展示，下单仍由服务端重算（客户端传金额一律不采信）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionUpgradeService {

    /** 订阅生效中 */
    public static final String STATUS_ACTIVE = "active";

    /** 无抵扣：从未购买 */
    private static final String REASON_NO_SUBSCRIPTION = "no_subscription";

    /** 无抵扣：同档续费（顺延，无重叠） */
    private static final String REASON_RENEWAL = "renewal";

    /** 无抵扣：积分已用尽 */
    private static final String REASON_CREDITS_EXHAUSTED = "credits_exhausted";

    /** 无抵扣：当前周期已接近到期，剩余价值不足 */
    private static final String REASON_PERIOD_NEARLY_OVER = "period_nearly_over";

    /** 无抵扣：查不到历史净支付额（不按标价猜，宁可抵扣为 0） */
    private static final String REASON_NO_PAID_AMOUNT = "no_paid_amount";

    private static final int SCALE_MONTHLY = 4;

    private final UserSubscriptionMapper userSubscriptionMapper;
    private final SubscriptionPlanMapper planMapper;
    private final CreditRewardMapper rewardMapper;

    /**
     * 折算报价。只读计算，无副作用（回收动作在下单事务里做）。
     *
     * @param targetPlan    目标套餐（上架校验由调用方完成）
     * @param targetBilling 目标计费周期
     * @throws BusinessException 目标档位低于已购档位（服务端硬拦降级，不依赖 UI 置灰）
     */
    public Quote quote(Long userId, SubscriptionPlan targetPlan, SubscriptionPlanBilling targetBilling) {
        BigDecimal originalAmount = targetBilling.getPrice();
        int targetMonths = targetBilling.getPeriodMonths() == null ? 1 : targetBilling.getPeriodMonths();
        LocalDateTime now = LocalDateTime.now();
        UserSubscription active = findActiveSubscription(userId);

        // ① 无生效订阅（免费版 / 已过期）→ 全价新购，弹框不显示抵扣行
        if (active == null) {
            return new Quote(SubscriptionOrder.TYPE_NEW, null,
                    BigDecimal.ZERO, targetMonths, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    originalAmount, BigDecimal.ZERO, BigDecimal.ZERO, originalAmount, BigDecimal.ZERO,
                    BigDecimal.ZERO, null, null,
                    REASON_NO_SUBSCRIPTION, null, now.plusMonths(targetMonths), false, 0);
        }

        // ② 同一套餐 → 续费：到期时间顺延，不适用抵扣（没有重叠，也没有替换）
        if (targetPlan.getCode().equals(active.getPlanCode())) {
            return new Quote(SubscriptionOrder.TYPE_RENEW, active.getPlanCode(),
                    nz(active.getNetPaidAmount()), monthsOf(active), 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    originalAmount, BigDecimal.ZERO, BigDecimal.ZERO, originalAmount, BigDecimal.ZERO,
                    BigDecimal.ZERO, active.getId(), null,
                    REASON_RENEWAL, active.getExpiresAt(), active.getExpiresAt().plusMonths(targetMonths),
                    false, 0);
        }

        // ③ 降级硬拦：已购套餐仍在生效时不允许买更低档（UI 已置灰，服务端必须自己再拦一次）
        SubscriptionPlan ownedPlan = planByCode(active.getPlanCode());
        if (ownedPlan != null && targetPlan.getSortOrder() != null && ownedPlan.getSortOrder() != null
                && targetPlan.getSortOrder() <= ownedPlan.getSortOrder()) {
            throw new BusinessException("暂不支持降级到更低的套餐，可在当前套餐到期后再购买");
        }

        // ④ 升级折算
        int n = monthsOf(active);
        BigDecimal netPaid = nz(active.getNetPaidAmount());
        BigDecimal monthly = netPaid.signum() > 0
                ? netPaid.divide(BigDecimal.valueOf(n), SCALE_MONTHLY, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        int currentPeriod = currentPeriodIndex(active, n, now);
        int unusedMonths = Math.max(n - currentPeriod, 0);

        LocalDateTime periodStart = active.getStartedAt().plusMonths(currentPeriod - 1L);
        LocalDateTime periodEnd = min(periodStart.plusMonths(1), active.getExpiresAt());
        BigDecimal ratioTime = ratioLeft(now, periodStart, periodEnd);

        CreditReward currentReward = findCurrentReward(active.getId(), currentPeriod);
        BigDecimal ratioCredit = BigDecimal.ONE;
        BigDecimal periodTotal = BigDecimal.ZERO;
        BigDecimal periodRemaining = BigDecimal.ZERO;
        if (currentReward != null && currentReward.getTotal() != null && currentReward.getTotal().signum() > 0) {
            periodTotal = currentReward.getTotal();
            periodRemaining = nz(currentReward.getRemaining());
            ratioCredit = periodRemaining.divide(periodTotal, SCALE_MONTHLY, RoundingMode.HALF_UP);
        }
        BigDecimal ratioApplied = ratioTime.min(ratioCredit);

        // 抵扣 = 未生效月全额 + 当月按取小比例；截断到不超过新套餐价（超出部分作废，不结转、不退现）
        BigDecimal unusedAmount = monthly.multiply(BigDecimal.valueOf(unusedMonths))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal discountRaw = monthly.multiply(BigDecimal.valueOf(unusedMonths))
                .add(monthly.multiply(ratioApplied))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal discount = discountRaw.min(originalAmount);
        BigDecimal wasted = discountRaw.subtract(discount).max(BigDecimal.ZERO);
        BigDecimal payAmount = originalAmount.subtract(discount).max(BigDecimal.ZERO);

        // 回收口径：与折钱同一个 k。数学上 ≤ 当月剩余 ≤ 账户余额，扣不穿
        BigDecimal reclaim = periodTotal.multiply(ratioApplied)
                .setScale(0, RoundingMode.HALF_UP)
                .min(periodRemaining);

        String noDiscountReason = null;
        if (discount.signum() <= 0) {
            if (netPaid.signum() <= 0) {
                noDiscountReason = REASON_NO_PAID_AMOUNT;
            } else if (ratioCredit.signum() <= 0) {
                noDiscountReason = REASON_CREDITS_EXHAUSTED;
            } else {
                noDiscountReason = REASON_PERIOD_NEARLY_OVER;
            }
        }

        LocalDateTime newExpiresAt = now.plusMonths(targetMonths);
        boolean shrunk = newExpiresAt.isBefore(active.getExpiresAt());
        int shrinkDays = shrunk
                ? (int) ChronoUnit.DAYS.between(newExpiresAt, active.getExpiresAt())
                : 0;

        log.debug("升级折算 userId={} n={} P={} m={} i={} F={} t={} u={} k={} G={} A={} reclaim={}",
                userId, n, netPaid, monthly, currentPeriod, unusedMonths,
                ratioTime, ratioCredit, ratioApplied, discount, payAmount, reclaim);

        return new Quote(SubscriptionOrder.TYPE_UPGRADE, active.getPlanCode(),
                netPaid, n, currentPeriod, unusedMonths,
                ratioTime, ratioCredit, ratioApplied,
                originalAmount, unusedAmount, discount, payAmount, wasted,
                reclaim, active.getId(), currentReward == null ? null : currentReward.getId(),
                noDiscountReason, active.getExpiresAt(), newExpiresAt, shrunk, shrinkDays);
    }

    /** 查「生效中且未到期」的订阅（取最晚到期的一条）；无则返回 null。 */
    public UserSubscription findActiveSubscription(Long userId) {
        if (userId == null) {
            return null;
        }
        return userSubscriptionMapper.selectOne(
                new LambdaQueryWrapper<UserSubscription>()
                        .eq(UserSubscription::getUserId, userId)
                        .eq(UserSubscription::getStatus, STATUS_ACTIVE)
                        .gt(UserSubscription::getExpiresAt, LocalDateTime.now())
                        .orderByDesc(UserSubscription::getExpiresAt)
                        .last("LIMIT 1")
        );
    }

    /** 覆盖总月数：优先用落库的 total_months，缺失时按起止时间推算（存量兼容）。 */
    private int monthsOf(UserSubscription subscription) {
        Integer total = subscription.getTotalMonths();
        if (total != null && total > 0) {
            return total;
        }
        if (subscription.getStartedAt() == null || subscription.getExpiresAt() == null) {
            return 1;
        }
        return Math.max(1, (int) ChronoUnit.MONTHS.between(subscription.getStartedAt(), subscription.getExpiresAt()));
    }

    /** 当前所处的期号（1-based）；越界一律夹到 [1, n]。 */
    private int currentPeriodIndex(UserSubscription subscription, int n, LocalDateTime now) {
        long elapsedMonths = subscription.getStartedAt() == null
                ? 0
                : ChronoUnit.MONTHS.between(subscription.getStartedAt(), now);
        int index = (int) Math.max(elapsedMonths + 1, 1);
        return Math.min(index, Math.max(n, 1));
    }

    /**
     * 当月剩余时间比例 = (期末 − now) / (期末 − 期初)，夹在 [0, 1]。
     * 自然到期时返回 0（抵扣归零），刚进入当月返回 1。
     */
    private BigDecimal ratioLeft(LocalDateTime now, LocalDateTime start, LocalDateTime end) {
        long total = ChronoUnit.SECONDS.between(start, end);
        if (total <= 0) {
            return BigDecimal.ZERO;
        }
        long left = ChronoUnit.SECONDS.between(now, end);
        if (left <= 0) {
            return BigDecimal.ZERO;
        }
        if (left >= total) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(left).divide(BigDecimal.valueOf(total), SCALE_MONTHLY, RoundingMode.HALF_UP);
    }

    /**
     * 定位「当月批次」。靠订阅归属 + 期号精确定位，
     * <b>不按时间区间撮合流水</b> —— 用户可能同时消耗加量包或活动积分，按区间统计会失真。
     */
    private CreditReward findCurrentReward(Long subscriptionId, int periodIndex) {
        if (subscriptionId == null) {
            return null;
        }
        return rewardMapper.selectOne(
                new LambdaQueryWrapper<CreditReward>()
                        .eq(CreditReward::getSubscriptionId, subscriptionId)
                        .eq(CreditReward::getPeriodIndex, periodIndex)
                        .last("LIMIT 1")
        );
    }

    private SubscriptionPlan planByCode(String code) {
        if (code == null) {
            return null;
        }
        return planMapper.selectOne(new LambdaQueryWrapper<SubscriptionPlan>()
                .eq(SubscriptionPlan::getCode, code)
                .last("LIMIT 1"));
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 折算结果（同一份同时供预览与下单，保证「看到的」与「算的」是同一个数）。
     *
     * @param orderType           new / renew / upgrade
     * @param ownedPlanCode       当前生效套餐 code，无订阅为 null
     * @param netPaidAmount       旧订阅净支付额（折算基数）
     * @param totalMonths         覆盖总月数 n
     * @param currentPeriodIndex  当前期号 i
     * @param unusedMonths        未生效整月数 F
     * @param ratioTime           当月剩余时间比例 t
     * @param ratioCredit         当月剩余积分比例 u
     * @param ratioApplied        实际采用的比例 k = min(t, u)
     * @param originalAmount      新套餐周期原价
     * @param unusedAmount        未生效月份全额退还的部分（{@code m × F}），展示「另有 N 个月尚未开始，已全额退还 ¥X」用
     * @param discountAmount      抵扣额 G（已截断到不超过原价）
     * @param payAmount           应付 A
     * @param wastedDiscount      因周期缩短而作废的抵扣额（> 0 时必须二次确认）
     * @param reclaimCredits      升级时应回收的旧套餐积分（= 当月批次额度 × k）
     * @param sourceSubscriptionId 抵扣来源订阅行
     * @param sourceRewardId      旧订阅当月批次 id（回收定位依据；null 表示不回收积分）
     * @param noDiscountReason    抵扣为 0 的原因；null 表示有抵扣或不适用
     * @param previousExpiresAt   升级前到期时间
     * @param newExpiresAt        本次购买后的到期时间
     * @param expiresShrunk       新到期日是否早于原到期日（周期缩短）
     * @param shrinkDays          提前的天数
     */
    public record Quote(
            String orderType,
            String ownedPlanCode,
            BigDecimal netPaidAmount,
            int totalMonths,
            int currentPeriodIndex,
            int unusedMonths,
            BigDecimal ratioTime,
            BigDecimal ratioCredit,
            BigDecimal ratioApplied,
            BigDecimal originalAmount,
            BigDecimal unusedAmount,
            BigDecimal discountAmount,
            BigDecimal payAmount,
            BigDecimal wastedDiscount,
            BigDecimal reclaimCredits,
            Long sourceSubscriptionId,
            Long sourceRewardId,
            String noDiscountReason,
            LocalDateTime previousExpiresAt,
            LocalDateTime newExpiresAt,
            boolean expiresShrunk,
            int shrinkDays
    ) {
    }
}

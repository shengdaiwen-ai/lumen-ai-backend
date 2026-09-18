package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.common.exception.BusinessException;
import com.lumenai.backend.dto.SubscriptionOrderVO;
import com.lumenai.backend.entity.BalanceTransaction;
import com.lumenai.backend.entity.SubscriptionCreditGrant;
import com.lumenai.backend.entity.SubscriptionOrder;
import com.lumenai.backend.entity.SubscriptionPlan;
import com.lumenai.backend.entity.SubscriptionPlanBilling;
import com.lumenai.backend.entity.User;
import com.lumenai.backend.entity.UserSubscription;
import com.lumenai.backend.mapper.BalanceTransactionMapper;
import com.lumenai.backend.mapper.SubscriptionOrderMapper;
import com.lumenai.backend.mapper.SubscriptionPlanBillingMapper;
import com.lumenai.backend.mapper.SubscriptionPlanMapper;
import com.lumenai.backend.mapper.UserMapper;
import com.lumenai.backend.mapper.UserSubscriptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订阅下单服务（余额支付）
 *
 * <p><b>一次支付 = 一个事务里的八件事</b>，任何一步失败全部回滚：</p>
 * <ol>
 *   <li>校验（套餐上架 / 周期属于该套餐 / 不允许降配 / 有效期上限 36 个月 / 作废额度需二次确认凭据）；</li>
 *   <li>折算（当前订阅 + 目标周期 → {@code n/P/m/i/F/t/u/k/G/A}，详见
 *       {@link SubscriptionUpgradeService}）；</li>
 *   <li>按库表价格<b>原子扣减</b> {@code users.balance}（条件更新，0 行即余额不足；应付为 0 时跳过）；</li>
 *   <li>写 {@code subscription_orders}（含原价 / 抵扣额 / 类型 / 来源订阅的完整账目快照）；</li>
 *   <li>写 {@code balance_transactions}（余额流水，规范 §10.3 要求金额变动必须留痕）；</li>
 *   <li>订阅行变更 + 预生成发放计划（新购 → 新增行；升级 → 旧行置 upgraded 并取消剩余计划、新增行；续费 → 原行顺延并追加计划）；</li>
 *   <li><b>发放新周期当期积分</b>（按订阅第 1 期立即发；续费顺延，等该周期开始时才发）；</li>
 *   <li><b>回收旧套餐当月未消耗积分</b>（升级时，按与折钱相同的比例 {@code k}）。</li>
 * </ol>
 *
 * <p><b>金额一律取库表</b>：请求只带套餐与周期的 code，不带金额 —— 客户端传价格等于把定价权交给客户端。
 * 同理，积分额度取 {@code subscription_plans.monthly_credits}，<b>绝不去解析权益文案里的数字</b>。</p>
 *
 * <p><b>三种订单类型的语义</b>：新购（无抵扣）／续费（同档顺延，无抵扣，因为不存在重叠）／
 * 升级（替换：旧订阅立即终止、新订阅从升级日起算，旧订阅剩余价值折成抵扣额）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionOrderService {

    /** 余额流水关联的业务类型 */
    private static final String REF_TYPE_SUBSCRIPTION_ORDER = "subscription_order";

    /** 生效中 */
    private static final String STATUS_ACTIVE = "active";

    /** 已升级替换（旧订阅行保留，不物理删除） */
    private static final String STATUS_UPGRADED = "upgraded";

    /** 上架 */
    private static final String PLAN_STATUS_ACTIVE = "active";

    /** 订阅有效期上限（防堆积，也防二期自动续费无限顺延） */
    private static final int MAX_TOTAL_MONTHS = 36;

    private static final DateTimeFormatter ORDER_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SubscriptionPlanMapper planMapper;
    private final SubscriptionPlanBillingMapper billingMapper;
    private final UserSubscriptionMapper userSubscriptionMapper;
    private final SubscriptionOrderMapper orderMapper;
    private final BalanceTransactionMapper balanceTransactionMapper;
    private final UserMapper userMapper;
    private final CreditService creditService;
    private final CreditGrantService creditGrantService;
    private final SubscriptionUpgradeService upgradeService;

    /**
     * 余额支付下单。写操作，事务内完成折算、扣款、开通/升级/续费、记流水、发积分与回收旧积分。
     *
     * @param planCode      套餐标识，如 standard / premium / flagship
     * @param billingCode   计费周期标识，如 monthly-auto / yearly
     * @param confirmWasted 用户是否已确认「周期缩短导致的抵扣作废」；
     *                      <b>有作废金额时必填 true</b>，否则拒绝下单 ——
     *                      这是防「绕过界面直调接口被静默截断」的服务端凭据
     */
    @Transactional(rollbackFor = Exception.class)
    public SubscriptionOrderVO payWithBalance(String planCode, String billingCode, Boolean confirmWasted) {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new BusinessException("请先登录后再购买套餐");
        }

        SubscriptionPlan plan = findPlan(planCode);
        SubscriptionPlanBilling billing = findBilling(plan.getId(), billingCode);
        BigDecimal originalAmount = billing.getPrice();
        if (originalAmount == null || originalAmount.signum() <= 0) {
            throw new BusinessException("该计费周期价格异常，暂时无法下单");
        }
        int months = billing.getPeriodMonths() == null ? 1 : billing.getPeriodMonths();
        LocalDateTime now = LocalDateTime.now();

        // ── 1 + 2. 校验与折算（与只读预览接口共用同一份算法，杜绝「看到的」与「算的」不一致）
        SubscriptionUpgradeService.Quote quote = upgradeService.quote(userId, plan, billing);

        if (quote.newExpiresAt().isAfter(now.plusMonths(MAX_TOTAL_MONTHS))) {
            throw new BusinessException(String.format(
                    "订阅有效期最长 %d 个月，本次购买会使有效期超出上限", MAX_TOTAL_MONTHS));
        }
        if (quote.wastedDiscount().signum() > 0 && !Boolean.TRUE.equals(confirmWasted)) {
            throw new BusinessException(String.format(
                    "升级后有效期会提前，其中 ¥%s 的抵扣额度会作废，请确认后再支付",
                    money(quote.wastedDiscount())));
        }

        BigDecimal payAmount = quote.payAmount();

        // ── 3. 原子扣款：条件更新与扣减同一条 SQL，0 行即余额不足（并发下不会扣穿）
        if (payAmount.signum() > 0 && userMapper.deductBalance(userId, payAmount) == 0) {
            throw new BusinessException(String.format(
                    "余额不足，当前余额 ¥%s，本次需支付 ¥%s，请先充值",
                    money(balanceOf(userId)), money(payAmount)));
        }
        BigDecimal balanceAfter = balanceOf(userId);

        // ── 4. 订单：金额三方全留痕（原价 / 抵扣 / 实付），对账时能看到完整的「抵了多少、付了多少」
        SubscriptionOrder order = new SubscriptionOrder();
        order.setOrderNo(nextOrderNo());
        order.setUserId(userId);
        order.setPlanCode(plan.getCode());
        order.setBillingCode(billing.getCode());
        order.setPlanName(plan.getName());
        order.setBillingLabel(billing.getLabel());
        order.setPeriodMonths(months);
        order.setOriginalAmount(originalAmount);
        order.setDiscountAmount(quote.discountAmount());
        order.setAmount(payAmount);
        order.setOrderType(quote.orderType());
        order.setSourceSubscriptionId(quote.sourceSubscriptionId());
        order.setPayMethod(SubscriptionOrder.PAY_METHOD_BALANCE);
        order.setStatus(SubscriptionOrder.STATUS_PAID);
        order.setPaidAt(now);
        orderMapper.insert(order);

        // ── 5. 余额流水（消费记负数；应付为 0 时没有资金变动，不写流水）
        if (payAmount.signum() > 0) {
            BalanceTransaction tx = new BalanceTransaction();
            tx.setUserId(userId);
            tx.setChangeAmount(payAmount.negate());
            tx.setBalanceAfter(balanceAfter);
            tx.setType(BalanceTransaction.TYPE_CONSUME);
            tx.setRefType(REF_TYPE_SUBSCRIPTION_ORDER);
            tx.setRefId(order.getId());
            tx.setRemark(remarkOf(plan, billing, quote.orderType(), quote.discountAmount()));
            balanceTransactionMapper.insert(tx);
        }

        // ── 6. 订阅行变更 + 预生成发放计划
        UserSubscription subscription = applySubscription(userId, plan, billing, quote, now, months, payAmount);

        // ── 7. 发放新周期当期积分：新购/升级立即发第 1 期；续费是往后顺延，要等新周期开始才发
        BigDecimal granted = BigDecimal.ZERO;
        if (!SubscriptionOrder.TYPE_RENEW.equals(quote.orderType())) {
            creditGrantService.grantDue(userId);
            int monthly = plan.getMonthlyCredits() == null ? 0 : plan.getMonthlyCredits();
            // 如实回报：以第 1 期计划的实际状态为准。不凭「计划已建」就假设积分已到账 ——
            // 否则一旦发放环节出了岔子，接口会向用户报一个根本没进账户的数字。
            SubscriptionCreditGrant first = creditGrantService.findGrant(subscription.getId(), 1);
            granted = (monthly > 0 && first != null
                    && SubscriptionCreditGrant.STATUS_GRANTED.equals(first.getStatus()))
                    ? BigDecimal.valueOf(monthly)
                    : BigDecimal.ZERO;
        }

        // ── 8. 回收旧套餐当月未消耗积分（与折钱同一个比例 k：钱退了，对应的货就要还）
        BigDecimal reclaimed = BigDecimal.ZERO;
        if (SubscriptionOrder.TYPE_UPGRADE.equals(quote.orderType())
                && quote.reclaimCredits().signum() > 0) {
            creditService.reclaimSubscriptionCredits(userId, quote.sourceRewardId(), quote.reclaimCredits(),
                    "升级回收·" + planNameOf(quote.ownedPlanCode()) + "当月剩余积分");
            reclaimed = quote.reclaimCredits();
        }

        // 金额恒等式断言（规范 §10.3）：三笔金额必须自洽，否则宁可整体回滚
        assertAmountIdentity(order, originalAmount, quote.discountAmount(), payAmount);

        BigDecimal creditsAfter = creditService.generalBalanceOf(userId);
        log.info("余额支付成功 userId={} orderNo={} type={} plan={} billing={} 原价={} 抵扣={} 实付={} " +
                        "余额={} 到期={} 本期积分={} 回收积分={} 账户积分={}",
                userId, order.getOrderNo(), quote.orderType(), plan.getCode(), billing.getCode(),
                originalAmount, quote.discountAmount(), payAmount,
                balanceAfter, subscription.getExpiresAt(), granted, reclaimed, creditsAfter);

        return SubscriptionOrderVO.builder()
                .orderNo(order.getOrderNo())
                .planCode(plan.getCode())
                .planName(plan.getName())
                .billingCode(billing.getCode())
                .billingLabel(billing.getLabel())
                .periodMonths(months)
                .amount(payAmount)
                .orderType(quote.orderType())
                .originalAmount(originalAmount)
                .discountAmount(quote.discountAmount())
                .wastedDiscount(quote.wastedDiscount())
                .reclaimedCredits(reclaimed)
                .unusedMonths(quote.unusedMonths())
                .ratioApplied(quote.ratioApplied())
                .payMethod(order.getPayMethod())
                .status(order.getStatus())
                .paidAt(now.format(MINUTE_FORMAT))
                .expiresAt(subscription.getExpiresAt().format(DAY_FORMAT))
                .balanceAfter(balanceAfter)
                .grantedCredits(granted)
                .creditsAfter(creditsAfter)
                .build();
    }

    /**
     * 订阅行变更（三种类型各一条路径）+ 预生成对应月份的发放计划。
     *
     * <p>升级是<b>替换</b>而不是叠加：旧订阅立即置 {@code upgraded} 并取消剩余计划，
     * 新订阅从升级日起算。这样同一用户任何时刻最多只有一条 active 订阅 ——
     * 这条不变量是所有抵扣计算的前提（否则「旧套餐剩余价值」根本无从定义）。
     * 之所以不用「并行叠加」，是因为那样用户会为重叠时长付两份钱、积分也发两份，
     * 旧行还会变成任何界面都不显示的孤儿。</p>
     */
    private UserSubscription applySubscription(Long userId,
                                              SubscriptionPlan plan,
                                              SubscriptionPlanBilling billing,
                                              SubscriptionUpgradeService.Quote quote,
                                              LocalDateTime now,
                                              int months,
                                              BigDecimal payAmount) {
        if (SubscriptionOrder.TYPE_RENEW.equals(quote.orderType())) {
            return renewSubscription(userId, plan, billing, quote, months, payAmount);
        }
        if (SubscriptionOrder.TYPE_UPGRADE.equals(quote.orderType())) {
            terminateReplacedSubscription(quote, plan, now);
        }
        return createSubscription(userId, plan, billing, now, months, payAmount);
    }

    /** 新购/升级后的新订阅行：从此刻起算，净支付额就是本次实付。 */
    private UserSubscription createSubscription(Long userId,
                                               SubscriptionPlan plan,
                                               SubscriptionPlanBilling billing,
                                               LocalDateTime now,
                                               int months,
                                               BigDecimal payAmount) {
        UserSubscription created = new UserSubscription();
        created.setUserId(userId);
        created.setPlanCode(plan.getCode());
        created.setBillingCode(billing.getCode());
        created.setStartedAt(now);
        created.setExpiresAt(now.plusMonths(months));
        created.setStatus(STATUS_ACTIVE);
        created.setNetPaidAmount(payAmount);
        created.setTotalMonths(months);
        created.setAutoRenew(Boolean.TRUE.equals(billing.getAutoRenew()));
        userSubscriptionMapper.insert(created);
        creditGrantService.createPlan(created.getId(), userId, 1, months, now, plan.getMonthlyCredits());
        return created;
    }

    /** 同档续费：到期时间<b>在原到期日上顺延</b>（不是从今天重算，否则续费会吃掉剩余时长）。 */
    private UserSubscription renewSubscription(Long userId,
                                               SubscriptionPlan plan,
                                               SubscriptionPlanBilling billing,
                                               SubscriptionUpgradeService.Quote quote,
                                               int months,
                                               BigDecimal payAmount) {
        UserSubscription active = loadActive(quote.sourceSubscriptionId());
        int oldMonths = (active.getTotalMonths() == null || active.getTotalMonths() < 1) ? 1 : active.getTotalMonths();
        BigDecimal oldPaid = active.getNetPaidAmount() == null ? BigDecimal.ZERO : active.getNetPaidAmount();
        LocalDateTime base = active.getExpiresAt();
        LocalDateTime expiresAt = base.plusMonths(months);

        // 条件更新：0 行说明订阅状态已变（并发下单/已被升级），抛异常回滚
        int updated = userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscription>()
                .eq(UserSubscription::getId, active.getId())
                .eq(UserSubscription::getStatus, STATUS_ACTIVE)
                .set(UserSubscription::getBillingCode, billing.getCode())
                .set(UserSubscription::getExpiresAt, expiresAt)
                .set(UserSubscription::getNetPaidAmount, oldPaid.add(payAmount))
                .set(UserSubscription::getTotalMonths, oldMonths + months)
                .set(UserSubscription::getAutoRenew, Boolean.TRUE.equals(billing.getAutoRenew())));
        if (updated == 0) {
            throw new BusinessException("订阅状态已变化（可能有并发操作），请刷新后重试");
        }

        // 追加后续月份的发放计划：第 oldMonths+1 期从「原到期日」开始，与顺延后的区间严丝合缝
        creditGrantService.createPlan(active.getId(), userId, oldMonths + 1, months, base, plan.getMonthlyCredits());

        active.setBillingCode(billing.getCode());
        active.setExpiresAt(expiresAt);
        active.setNetPaidAmount(oldPaid.add(payAmount));
        active.setTotalMonths(oldMonths + months);
        return active;
    }

    /**
     * 升级时终止被替换的订阅：置 {@code upgraded} + 记录去向与结束时间，并取消剩余未发放的计划。
     *
     * <p>取消计划的必要性：未生效月份的积分<b>从未发放</b>，只要把计划标记为 cancelled 它就不会再发。
     * 这也是本方案比「一次发满」简单的地方 —— 那边升级时得把未来 11 个月的积分批量收回。</p>
     */
    private void terminateReplacedSubscription(SubscriptionUpgradeService.Quote quote,
                                               SubscriptionPlan targetPlan,
                                               LocalDateTime now) {
        int updated = userSubscriptionMapper.update(null, new LambdaUpdateWrapper<UserSubscription>()
                .eq(UserSubscription::getId, quote.sourceSubscriptionId())
                .eq(UserSubscription::getStatus, STATUS_ACTIVE)
                .set(UserSubscription::getStatus, STATUS_UPGRADED)
                .set(UserSubscription::getEndedAt, now)
                .set(UserSubscription::getUpgradedToPlanCode, targetPlan.getCode()));
        if (updated == 0) {
            throw new BusinessException("订阅状态已变化（可能有并发操作），请刷新后重试");
        }
        int cancelled = creditGrantService.cancelPending(quote.sourceSubscriptionId());
        log.info("升级替换：旧订阅 {} 已终止，取消未发放计划 {} 期", quote.sourceSubscriptionId(), cancelled);
    }

    private UserSubscription loadActive(Long subscriptionId) {
        UserSubscription active = subscriptionId == null ? null : userSubscriptionMapper.selectById(subscriptionId);
        if (active == null || !STATUS_ACTIVE.equals(active.getStatus())) {
            throw new BusinessException("当前订阅状态已变化，请刷新后重试");
        }
        return active;
    }

    private SubscriptionPlan findPlan(String planCode) {
        SubscriptionPlan plan = planMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionPlan>()
                        .eq(SubscriptionPlan::getCode, planCode)
                        .eq(SubscriptionPlan::getStatus, PLAN_STATUS_ACTIVE)
                        .last("LIMIT 1")
        );
        if (plan == null) {
            throw new BusinessException("该套餐不存在或已下架，请刷新后重试");
        }
        return plan;
    }

    private SubscriptionPlanBilling findBilling(Long planId, String billingCode) {
        SubscriptionPlanBilling billing = billingMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionPlanBilling>()
                        .eq(SubscriptionPlanBilling::getPlanId, planId)
                        .eq(SubscriptionPlanBilling::getCode, billingCode)
                        .last("LIMIT 1")
        );
        if (billing == null) {
            throw new BusinessException("该计费周期不存在，请重新选择");
        }
        return billing;
    }

    /** 套餐名（用于流水备注），查不到时回落 code。 */
    private String planNameOf(String planCode) {
        if (planCode == null) {
            return "原套餐";
        }
        SubscriptionPlan plan = planMapper.selectOne(new LambdaQueryWrapper<SubscriptionPlan>()
                .eq(SubscriptionPlan::getCode, planCode)
                .last("LIMIT 1"));
        return plan == null ? planCode : plan.getName();
    }

    /** 流水备注：把「抵了多少」写进去，用户查流水时能看懂实付为什么小于原价。 */
    private String remarkOf(SubscriptionPlan plan,
                            SubscriptionPlanBilling billing,
                            String orderType,
                            BigDecimal discount) {
        String action = switch (orderType) {
            case SubscriptionOrder.TYPE_UPGRADE -> "升级为";
            case SubscriptionOrder.TYPE_RENEW -> "续费";
            default -> "购买";
        };
        String base = action + plan.getName() + "·" + billing.getLabel();
        return discount.signum() > 0
                ? String.format("%s（已抵扣 ¥%s）", base, money(discount))
                : base;
    }

    /** 订单金额恒等式：{@code amount = original_amount − discount_amount}，不成立即回滚整笔。 */
    private void assertAmountIdentity(SubscriptionOrder order,
                                      BigDecimal originalAmount,
                                      BigDecimal discountAmount,
                                      BigDecimal payAmount) {
        if (originalAmount.subtract(discountAmount).compareTo(payAmount) != 0) {
            log.error("订单金额恒等式不成立 orderNo={} 原价={} 抵扣={} 实付={}",
                    order.getOrderNo(), originalAmount, discountAmount, payAmount);
            throw new BusinessException("订单金额计算异常，本次支付已取消，请重试");
        }
    }

    private BigDecimal balanceOf(Long userId) {
        User user = userMapper.selectById(userId);
        return (user == null || user.getBalance() == null) ? BigDecimal.ZERO : user.getBalance();
    }

    /** 订单号：SUB + 毫秒级时间戳 + 4 位随机，长度 24 ≤ order_no(40)。 */
    private String nextOrderNo() {
        return "SUB" + LocalDateTime.now().format(ORDER_NO_FORMAT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    /** 金额展示：去掉无意义的小数位（70.00 → 70） */
    private String money(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }
}

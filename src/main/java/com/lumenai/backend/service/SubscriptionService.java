package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.common.exception.BusinessException;
import com.lumenai.backend.dto.SubscriptionPlanVO;
import com.lumenai.backend.dto.SubscriptionPreviewVO;
import com.lumenai.backend.dto.SubscriptionPlanVO.BillingOption;
import com.lumenai.backend.dto.SubscriptionPlanVO.Feature;
import com.lumenai.backend.entity.SubscriptionOrder;
import com.lumenai.backend.entity.SubscriptionPlan;
import com.lumenai.backend.entity.SubscriptionPlanBilling;
import com.lumenai.backend.entity.SubscriptionPlanFeature;
import com.lumenai.backend.entity.User;
import com.lumenai.backend.entity.UserSubscription;
import com.lumenai.backend.mapper.SubscriptionPlanBillingMapper;
import com.lumenai.backend.mapper.SubscriptionPlanFeatureMapper;
import com.lumenai.backend.mapper.SubscriptionPlanMapper;
import com.lumenai.backend.mapper.UserMapper;
import com.lumenai.backend.mapper.UserSubscriptionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 订阅套餐服务
 *
 * <p>套餐数据来自 subscription_plans / subscription_plan_features /
 * subscription_plan_billings 三张表，改价、上下架、调整权益均由数据驱动，
 * 不需要改代码或重新发版。</p>
 *
 * <p><b>选中与可购规则</b>（全部由服务端算好下发，前端只消费标记、不判断套餐等级）：
 * <ol>
 *   <li><b>默认选中「已购套餐的下一档」</b>——这个弹框是「升级」入口，落点应是用户下一步要买的那档；
 *       已是最高档时选中自身（此时只能续费）。从未购买 / 已过期 / 已取消则回落到
 *       {@code is_default} 标记的套餐（标准版）。</li>
 *   <li><b>{@code locked}</b>——低于已购档的一律锁定；等于已购档时仅当存在更高档才锁定
 *       （否则等于允许降级、或把同一档重复卖一次）；已是最高档则放开自身以便续费。</li>
 *   <li><b>默认计费周期</b>——优先沿用用户已购订阅的 {@code billing_code}（该 code 三档套餐通用），
 *       没有则用套餐自己的 {@code is_default}。</li>
 * </ol>
 *
 * <p>「档位」即 {@code subscription_plans.sort_order} 升序（标准版 → 高级版 → 旗舰版），
 * 不引入额外的等级字段，运营调顺序即调档位。</p>
 */
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    /** 订阅生效中 */
    private static final String STATUS_ACTIVE = "active";

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SubscriptionPlanMapper planMapper;
    private final SubscriptionPlanFeatureMapper featureMapper;
    private final SubscriptionPlanBillingMapper billingMapper;
    private final UserSubscriptionMapper userSubscriptionMapper;
    private final UserMapper userMapper;
    private final SubscriptionUpgradeService upgradeService;

    /** 查询上架套餐（含权益与计费周期）。只读查询，幂等。 */
    public List<SubscriptionPlanVO> getPlans() {
        List<SubscriptionPlan> plans = planMapper.selectList(
                new LambdaQueryWrapper<SubscriptionPlan>()
                        .eq(SubscriptionPlan::getStatus, "active")
                        .orderByAsc(SubscriptionPlan::getSortOrder)
        );
        if (plans.isEmpty()) {
            return List.of();
        }

        List<Long> planIds = plans.stream().map(SubscriptionPlan::getId).toList();

        Map<Long, List<SubscriptionPlanFeature>> featuresByPlan = featureMapper.selectList(
                        new LambdaQueryWrapper<SubscriptionPlanFeature>()
                                .in(SubscriptionPlanFeature::getPlanId, planIds)
                                .orderByAsc(SubscriptionPlanFeature::getSortOrder)
                ).stream()
                .collect(Collectors.groupingBy(SubscriptionPlanFeature::getPlanId));

        Map<Long, List<SubscriptionPlanBilling>> billingsByPlan = billingMapper.selectList(
                        new LambdaQueryWrapper<SubscriptionPlanBilling>()
                                .in(SubscriptionPlanBilling::getPlanId, planIds)
                                .orderByAsc(SubscriptionPlanBilling::getSortOrder)
                ).stream()
                .collect(Collectors.groupingBy(SubscriptionPlanBilling::getPlanId));

        // 已购且未过期 → 默认落到它的下一档；未购买 / 已过期 / 已购套餐已下架 → 回落到 is_default 套餐
        UserSubscription active = findActiveSubscription(UserContext.get());
        String ownedPlanCode = active != null ? active.getPlanCode() : null;
        int ownedIndex = indexOfCode(plans, ownedPlanCode);
        String selectedPlanCode = ownedIndex < 0
                ? defaultPlanCode(plans)
                : plans.get(Math.min(ownedIndex + 1, plans.size() - 1)).getCode();

        List<SubscriptionPlanVO> result = new ArrayList<>(plans.size());
        for (int i = 0; i < plans.size(); i++) {
            SubscriptionPlan plan = plans.get(i);
            // 低于已购档：永远锁定；等于已购档：仅当存在更高档时锁定（最高档放开自身，允许续费）
            boolean locked = ownedIndex >= 0
                    && (i < ownedIndex || (i == ownedIndex && ownedIndex + 1 < plans.size()));
            result.add(toVO(
                    plan,
                    featuresByPlan.getOrDefault(plan.getId(), Collections.emptyList()),
                    billingsByPlan.getOrDefault(plan.getId(), Collections.emptyList()),
                    selectedPlanCode,
                    ownedPlanCode,
                    active,
                    locked
            ));
        }
        return result;
    }

    /**
     * 当前生效套餐（含名称与 code）。<b>无生效订阅返回 null</b> —— 调用方据此决定展示"免费版"
     * 还是套餐名；兜底文案属于展示层，不要在这里硬编码。
     *
     * <p>这是「用户当前套餐」在全项目的唯一出口：users 表的 plan 列已删除（那列只在注册时写过
     * 一次 "free"，买套餐后从不更新），任何需要展示或判断套餐的地方都应调用本方法。</p>
     */
    public SubscriptionPlan currentActivePlan(Long userId) {
        UserSubscription active = findActiveSubscription(userId);
        if (active == null || active.getPlanCode() == null) {
            return null;
        }
        return planMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionPlan>()
                        .eq(SubscriptionPlan::getCode, active.getPlanCode())
                        .last("LIMIT 1")
        );
    }

    /**
     * 升级折算预览（只读，无副作用）。
     *
     * <p>供订阅弹框在用户切换套餐/周期时调用，回答「这次升级省了多少、为什么是这个数」。
     * 所有金额、天数、比例均由服务端计算下发 —— <b>前端不做任何计价</b>。</p>
     *
     * <p>与下单共用 {@link SubscriptionUpgradeService#quote}，所以预览值与成交值不会漂移；
     * 且下单时仍会重算（预览接口的结果不参与成交）。</p>
     */
    public SubscriptionPreviewVO preview(String planCode, String billingCode) {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new BusinessException("请先登录后再查看套餐价格");
        }
        SubscriptionPlan plan = planMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionPlan>()
                        .eq(SubscriptionPlan::getCode, planCode)
                        .eq(SubscriptionPlan::getStatus, "active")
                        .last("LIMIT 1")
        );
        if (plan == null) {
            throw new BusinessException("该套餐不存在或已下架，请刷新后重试");
        }
        SubscriptionPlanBilling billing = billingMapper.selectOne(
                new LambdaQueryWrapper<SubscriptionPlanBilling>()
                        .eq(SubscriptionPlanBilling::getPlanId, plan.getId())
                        .eq(SubscriptionPlanBilling::getCode, billingCode)
                        .last("LIMIT 1")
        );
        if (billing == null) {
            throw new BusinessException("该计费周期不存在，请重新选择");
        }

        SubscriptionUpgradeService.Quote quote = upgradeService.quote(userId, plan, billing);
        UserSubscription active = upgradeService.findActiveSubscription(userId);
        BigDecimal balance = balanceOf(userId);

        return SubscriptionPreviewVO.builder()
                .planCode(plan.getCode())
                .planName(plan.getName())
                .billingCode(billing.getCode())
                .billingLabel(billing.getLabel())
                .originalAmount(quote.originalAmount())
                .unusedAmount(quote.unusedAmount())
                .discountAmount(quote.discountAmount())
                .payAmount(quote.payAmount())
                .wastedDiscount(quote.wastedDiscount())
                .reclaimCredits(quote.reclaimCredits())
                .orderType(quote.orderType())
                .ownedPlanCode(quote.ownedPlanCode())
                .ownedPlanName(active == null ? null : planNameOf(active.getPlanCode()))
                // 「旧订阅」相关的字段只在真有生效订阅时下发，避免无订阅时展示出无意义的 0 / 1
                .remainingDays(active == null ? null : remainingDays(active))
                .ownedMonths(active == null ? null : quote.totalMonths())
                .currentPeriodIndex(active == null ? null : quote.currentPeriodIndex())
                .unusedMonths(quote.unusedMonths())
                .ratioTime(quote.ratioTime())
                .ratioCredit(quote.ratioCredit())
                .ratioApplied(quote.ratioApplied())
                .noDiscountReason(quote.noDiscountReason())
                .newExpiresAt(quote.newExpiresAt().format(DAY_FORMAT))
                .previousExpiresAt(quote.previousExpiresAt() == null
                        ? null : quote.previousExpiresAt().format(DAY_FORMAT))
                .expiresShrunk(quote.expiresShrunk())
                .shrinkDays(quote.shrinkDays())
                .balanceEnough(balance.compareTo(quote.payAmount()) >= 0)
                .balance(balance)
                // 按月发放：新购/升级当期立即发第 1 期；续费顺延，等新周期开始才发
                .grantedCredits(SubscriptionOrder.TYPE_RENEW.equals(quote.orderType())
                        ? 0
                        : (plan.getMonthlyCredits() == null ? 0 : plan.getMonthlyCredits()))
                .build();
    }

    /** 剩余天数：不足一天按 1 天计（避免展示「剩余 0 天」却仍可使用）。 */
    private int remainingDays(UserSubscription subscription) {
        long seconds = ChronoUnit.SECONDS.between(LocalDateTime.now(), subscription.getExpiresAt());
        return seconds <= 0 ? 0 : (int) Math.ceil(seconds / 86400.0);
    }

    /** 套餐名（查不到时回落 code，不抛异常 —— 展示兜底不该阻断流程）。 */
    private String planNameOf(String planCode) {
        if (planCode == null) {
            return null;
        }
        SubscriptionPlan plan = planMapper.selectOne(new LambdaQueryWrapper<SubscriptionPlan>()
                .eq(SubscriptionPlan::getCode, planCode)
                .last("LIMIT 1"));
        return plan == null ? planCode : plan.getName();
    }

    private BigDecimal balanceOf(Long userId) {
        User user = userMapper.selectById(userId);
        return (user == null || user.getBalance() == null) ? BigDecimal.ZERO : user.getBalance();
    }

    /**
     * 查指定用户「生效中且未到期」的订阅（取最晚到期的一条）。
     * userId 为空 / 无记录 / 已过期均返回 null，由调用方回落到默认套餐。
     */
    private UserSubscription findActiveSubscription(Long userId) {
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

    /** 默认套餐：优先 is_default，其次排序第一。 */
    private String defaultPlanCode(List<SubscriptionPlan> plans) {
        return plans.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsDefault()))
                .map(SubscriptionPlan::getCode)
                .findFirst()
                .orElse(plans.get(0).getCode());
    }

    /** 套餐 code 在档位序列中的下标；未购买、或已购套餐已下架（不在上架列表里）返回 -1。 */
    private int indexOfCode(List<SubscriptionPlan> plans, String code) {
        if (code == null) {
            return -1;
        }
        for (int i = 0; i < plans.size(); i++) {
            if (code.equals(plans.get(i).getCode())) {
                return i;
            }
        }
        return -1;
    }

    private SubscriptionPlanVO toVO(SubscriptionPlan plan,
                                    List<SubscriptionPlanFeature> features,
                                    List<SubscriptionPlanBilling> billings,
                                    String selectedPlanCode,
                                    String ownedPlanCode,
                                    UserSubscription active,
                                    boolean locked) {
        boolean selected = plan.getCode().equals(selectedPlanCode);
        // owned 表达「这是用户当前持有的那一档」，与选中项无关（选中项现在是它的下一档）
        boolean owned = active != null && plan.getCode().equals(ownedPlanCode);

        // 默认计费周期：优先沿用用户已购周期（code 跨套餐通用），否则用该套餐自己的 is_default
        String preferredBillingCode = active != null ? active.getBillingCode() : null;

        List<BillingOption> options = billings.stream()
                .map(b -> BillingOption.builder()
                        .id(b.getCode())
                        .label(b.getLabel())
                        .discountLabel(b.getDiscountLabel())
                        .price(b.getPrice())
                        .unit(b.getUnit())
                        .periodMonths(b.getPeriodMonths() == null ? 1 : b.getPeriodMonths())
                        .defaultOption(preferredBillingCode != null
                                ? b.getCode().equals(preferredBillingCode)
                                : Boolean.TRUE.equals(b.getIsDefault()))
                        .build())
                .toList();

        // 兜底：已购周期若已下架（数据变更），保证该套餐仍有且仅有一个默认选项
        if (preferredBillingCode != null && options.stream().noneMatch(BillingOption::isDefaultOption)
                && !options.isEmpty()) {
            options.get(0).setDefaultOption(true);
        }

        return SubscriptionPlanVO.builder()
                .id(plan.getCode())
                .name(plan.getName())
                .description(plan.getDescription())
                .selected(selected)
                .owned(owned)
                .ownedUntil(owned ? active.getExpiresAt().format(DAY_FORMAT) : null)
                .locked(locked)
                .features(features.stream()
                        .map(f -> Feature.builder().icon(f.getIcon()).text(f.getContent()).build())
                        .toList())
                .billingOptions(options)
                .build();
    }
}

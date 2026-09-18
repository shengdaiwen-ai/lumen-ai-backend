package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lumenai.backend.common.exception.BusinessException;
import com.lumenai.backend.entity.CreditReward;
import com.lumenai.backend.entity.SubscriptionCreditGrant;
import com.lumenai.backend.entity.SubscriptionPlan;
import com.lumenai.backend.entity.UserSubscription;
import com.lumenai.backend.mapper.CreditAccountMapper;
import com.lumenai.backend.mapper.CreditRewardMapper;
import com.lumenai.backend.mapper.SubscriptionCreditGrantMapper;
import com.lumenai.backend.mapper.SubscriptionPlanMapper;
import com.lumenai.backend.mapper.UserSubscriptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订阅积分「按月发放」服务
 *
 * <p>一次订阅 = 覆盖总月数份<b>月度权益包</b>，每期一份积分。三件事：</p>
 * <ol>
 *   <li><b>预生成整段计划</b>（下单事务内）：年付 = 12 行，第 1 期立即发放，其余 {@code pending}；</li>
 *   <li><b>惰性补发</b>（{@link #grantDue}）：在读积分、登录、下单等入口把「已到点未发放」的期数补齐
 *       —— <b>不引定时任务</b>：零调度器、无单点、停机不丢；代价只是「用户长期不登录则不到账」，
 *       而他不登录也就不消耗，不构成损失；</li>
 *   <li><b>升级时取消剩余计划</b>：未生效月的积分从未发出，所以升级退款不需要回收未来月的积分。</li>
 * </ol>
 *
 * <p><b>幂等由两道锁保证</b>：先按 {@code uk_sub_period(subscription_id, period_index)} 唯一键插入去重，
 * 再用「{@code WHERE status = 'pending'} 条件更新抢占」避免并发重复入账（0 行即已被别的请求发过）。</p>
 *
 * <p>为什么改按月发放：未来月份的积分如果已经躺在用户账户里，「退未生效月的钱」就等于
 * 用户既拿退款又留积分（双重补偿）。按月发放后，退款口径从「防套利的保守阀」变成「按交付事实如实结算」。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditGrantService {

    private final SubscriptionCreditGrantMapper grantMapper;
    private final UserSubscriptionMapper subscriptionMapper;
    private final SubscriptionPlanMapper planMapper;
    private final CreditAccountMapper accountMapper;
    private final CreditRewardMapper rewardMapper;
    private final CreditService creditService;

    /**
     * 预生成发放计划（下单事务内调用，幂等）。
     *
     * @param subscriptionId 订阅行 id（必须已落库）
     * @param fromPeriod     起始期号：新购/升级 = 1；续费追加 = 原有期数 + 1
     * @param months         生成几期
     * @param baseStart      第 {@code fromPeriod} 期的开始时间：新购/升级 = 下单时刻；
     *                       续费 = 原到期时间（续费是往后顺延，不是从今天重算）
     * @param monthlyCredits 每期积分（下单时的套餐额度快照）
     */
    public int createPlan(Long subscriptionId, Long userId, int fromPeriod, int months,
                          LocalDateTime baseStart, Integer monthlyCredits) {
        if (months <= 0 || fromPeriod <= 0) {
            return 0;
        }
        BigDecimal amount = (monthlyCredits == null || monthlyCredits <= 0)
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(monthlyCredits);
        // 必须先截断到整秒再落库：period_start 是 DATETIME（精度到秒），而 MySQL 降精度时是
        // 「四舍五入」不是截断 —— 带 0.5 秒以上纳秒的 baseStart 会被写大最多 1 秒。紧随其后的
        // grantDue 拿 LocalDateTime.now() 去比（传参保留纳秒），就会把第 1 期判成「还没到点」，
        // 于是新购/升级当期积分静默不发，直到下一次访问恰好跨过那一秒才补上（约 50% 命中）。
        LocalDateTime base = baseStart.withNano(0);
        int inserted = 0;
        for (int i = 0; i < months; i++) {
            SubscriptionCreditGrant grant = new SubscriptionCreditGrant();
            grant.setSubscriptionId(subscriptionId);
            grant.setUserId(userId);
            grant.setPeriodIndex(fromPeriod + i);
            grant.setPeriodStart(base.plusMonths(i));
            grant.setPeriodEnd(base.plusMonths(i + 1));
            grant.setAmount(amount);
            grant.setStatus(SubscriptionCreditGrant.STATUS_PENDING);
            try {
                grantMapper.insert(grant);
                inserted++;
            } catch (Exception e) {
                // 唯一键冲突 = 该期已生成过（延迟/并发重入），跳过即可，不阻断下单
                log.debug("发放计划已存在，跳过 subscriptionId={} period={}", subscriptionId, fromPeriod + i);
            }
        }
        return inserted;
    }

    /**
     * 取消某订阅剩余未发放的计划（升级/终止时调用）。<b>不物理删除</b> —— 保留痕迹可追溯。
     *
     * @return 被取消的期数
     */
    public int cancelPending(Long subscriptionId) {
        if (subscriptionId == null) {
            return 0;
        }
        return grantMapper.update(null, new LambdaUpdateWrapper<SubscriptionCreditGrant>()
                .eq(SubscriptionCreditGrant::getSubscriptionId, subscriptionId)
                .eq(SubscriptionCreditGrant::getStatus, SubscriptionCreditGrant.STATUS_PENDING)
                .set(SubscriptionCreditGrant::getStatus, SubscriptionCreditGrant.STATUS_CANCELLED));
    }

    /**
     * 查某订阅某一期的发放计划（只读）。下单后用于**如实回报**本期是否真的入账。
     *
     * @param periodIndex 期号（1-based）
     * @return 计划行；不存在返回 null
     */
    public SubscriptionCreditGrant findGrant(Long subscriptionId, int periodIndex) {
        if (subscriptionId == null || periodIndex <= 0) {
            return null;
        }
        return grantMapper.selectOne(new LambdaQueryWrapper<SubscriptionCreditGrant>()
                .eq(SubscriptionCreditGrant::getSubscriptionId, subscriptionId)
                .eq(SubscriptionCreditGrant::getPeriodIndex, periodIndex)
                .last("LIMIT 1"));
    }

    /**
     * 惰性补发：把该用户所有「已到点但未发放」的期数补齐。
     *
     * <p>入口：登录/取用户信息、积分总览、奖励列表、下单事务。重复调用安全（两道幂等锁）。</p>
     *
     * <p>必须整体事务：先抢占状态再入账，若入账失败要把状态一起回滚 ——
     * 否则会留下「标记为已发但账户没到账」的永久缺口。</p>
     *
     * @return 本次实际发放的期数
     */
    @Transactional(rollbackFor = Exception.class)
    public int grantDue(Long userId) {
        if (userId == null) {
            return 0;
        }
        List<SubscriptionCreditGrant> due = grantMapper.selectList(
                new LambdaQueryWrapper<SubscriptionCreditGrant>()
                        .eq(SubscriptionCreditGrant::getUserId, userId)
                        .eq(SubscriptionCreditGrant::getStatus, SubscriptionCreditGrant.STATUS_PENDING)
                        .le(SubscriptionCreditGrant::getPeriodStart, LocalDateTime.now())
                        .orderByAsc(SubscriptionCreditGrant::getPeriodStart)
                        .orderByAsc(SubscriptionCreditGrant::getId)
        );
        if (due.isEmpty()) {
            return 0;
        }
        Map<Long, String> planNames = planNamesOf(
                due.stream().map(SubscriptionCreditGrant::getSubscriptionId).collect(Collectors.toSet()));
        int granted = 0;
        for (SubscriptionCreditGrant grant : due) {
            if (grantOne(grant, planNames)) {
                granted++;
            }
        }
        if (granted > 0) {
            log.info("积分按月发放完成 userId={} 本期发放 {} 期", userId, granted);
        }
        return granted;
    }

    /** 发放单期：先条件更新抢占状态（幂等），再入账 + 落批次，最后回填批次 id。 */
    private boolean grantOne(SubscriptionCreditGrant grant, Map<Long, String> planNames) {
        int claimed = grantMapper.update(null, new LambdaUpdateWrapper<SubscriptionCreditGrant>()
                .eq(SubscriptionCreditGrant::getId, grant.getId())
                .eq(SubscriptionCreditGrant::getStatus, SubscriptionCreditGrant.STATUS_PENDING)
                .set(SubscriptionCreditGrant::getStatus, SubscriptionCreditGrant.STATUS_GRANTED)
                .set(SubscriptionCreditGrant::getGrantedAt, LocalDateTime.now()));
        if (claimed == 0) {
            // 已被并发请求发放（多端登录、连点积分页），跳过
            return false;
        }
        BigDecimal amount = grant.getAmount();
        if (amount == null || amount.signum() <= 0) {
            // 套餐未配置额度：计划标记为已发放即可，不入账（正常路径，不是异常）
            return true;
        }
        creditService.ensureAccounts(grant.getUserId());
        if (accountMapper.addBalance(grant.getUserId(), CreditService.TYPE_GENERAL, amount) == 0) {
            throw new BusinessException("积分账户初始化失败，本期积分发放已取消，请重试");
        }
        String planName = planNames.get(grant.getSubscriptionId());
        CreditReward reward = new CreditReward();
        reward.setUserId(grant.getUserId());
        reward.setTitle((planName == null ? "订阅积分" : planName) + "·第 " + grant.getPeriodIndex() + " 期");
        reward.setCreditType(CreditService.TYPE_GENERAL);
        reward.setRemaining(amount);
        reward.setTotal(amount);
        reward.setExpiresAt(grant.getPeriodEnd());
        reward.setBatchCount(1);
        reward.setSubscriptionId(grant.getSubscriptionId());
        reward.setPeriodIndex(grant.getPeriodIndex());
        reward.setSortOrder(CreditService.REWARD_SORT_SUBSCRIPTION);
        rewardMapper.insert(reward);

        grantMapper.update(null, new LambdaUpdateWrapper<SubscriptionCreditGrant>()
                .eq(SubscriptionCreditGrant::getId, grant.getId())
                .set(SubscriptionCreditGrant::getRewardId, reward.getId()));
        return true;
    }

    /** 批量取「订阅 id → 套餐名」，用于批次标题（只在发放时查一次，避免逐期查库）。 */
    private Map<Long, String> planNamesOf(Set<Long> subscriptionIds) {
        if (subscriptionIds == null || subscriptionIds.isEmpty()) {
            return Map.of();
        }
        List<UserSubscription> subs = subscriptionMapper.selectBatchIds(subscriptionIds);
        Set<String> codes = subs.stream()
                .map(UserSubscription::getPlanCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> nameByCode = codes.isEmpty()
                ? Map.of()
                : planMapper.selectList(new LambdaQueryWrapper<SubscriptionPlan>()
                        .in(SubscriptionPlan::getCode, codes)).stream()
                .collect(Collectors.toMap(SubscriptionPlan::getCode, SubscriptionPlan::getName, (a, b) -> a));
        Map<Long, String> result = new HashMap<>();
        for (UserSubscription s : subs) {
            result.put(s.getId(), nameByCode.get(s.getPlanCode()));
        }
        return result;
    }
}

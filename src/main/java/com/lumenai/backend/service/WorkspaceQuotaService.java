package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumenai.backend.entity.SubscriptionPlan;
import com.lumenai.backend.entity.SubscriptionPlanQuota;
import com.lumenai.backend.entity.Workspace;
import com.lumenai.backend.mapper.SubscriptionPlanQuotaMapper;
import com.lumenai.backend.mapper.WorkspaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 工作空间配额：读 {@code subscription_plan_quotas} → 档位上限 + 已用数。
 *
 * <p>限额的来源必须是<b>数值表</b>而不是权益文案表 —— {@code subscription_plan_features}
 * 存的是「支持 3 个工作空间」这种给人看的句子，解析它等于把运营文案当 API（红线 14）。
 * 这里也没有任何硬编码的档位常量。</p>
 *
 * <p>降级口径：订阅过期导致「已用 &gt; 上限」时<b>只冻结新建</b>，绝不删既有空间
 * （删用户数据是不可逆的，而且这些空间代表的磁盘目录还在）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceQuotaService {

    /** 档位上限表里查不到时的兜底（比任何真实档位都紧，宁可挡住也不放开） */
    private static final int FALLBACK_LIMIT = 0;

    private final WorkspaceMapper workspaceMapper;
    private final SubscriptionPlanQuotaMapper quotaMapper;
    private final SubscriptionService subscriptionService;

    /** 当前生效档位 code；未订阅返回 {@code free}（与「无生效订阅」这一事实对应） */
    public String currentPlanCode(Long userId) {
        try {
            SubscriptionPlan plan = subscriptionService.currentActivePlan(userId);
            if (plan != null && plan.getCode() != null && !plan.getCode().isBlank()) {
                return plan.getCode();
            }
        } catch (Exception e) {
            log.warn("读取当前套餐失败，按 free 处理 userId={}", userId, e);
        }
        return SubscriptionPlanQuota.PLAN_FREE;
    }

    /** 工作空间数量上限；{@code -1} = 不限 */
    public int workspaceLimit(Long userId) {
        String planCode = currentPlanCode(userId);
        Integer limit = queryQuota(planCode, SubscriptionPlanQuota.KEY_WORKSPACE_LIMIT);
        if (limit == null) {
            // 档位没配额度：记一条 warn 便于运营发现漏配，同时按最紧策略处理
            log.warn("套餐 {} 未配置 {}，按 {} 处理", planCode,
                    SubscriptionPlanQuota.KEY_WORKSPACE_LIMIT, FALLBACK_LIMIT);
            return FALLBACK_LIMIT;
        }
        return limit;
    }

    /** 已登记的空间数（跨设备合计 —— 上限是对账号的，不是对设备的） */
    public int usedCount(Long userId) {
        Long n = workspaceMapper.selectCount(
                new LambdaQueryWrapper<Workspace>().eq(Workspace::getUserId, userId));
        return n == null ? 0 : n.intValue();
    }

    /** 是否还能再建一个空间 */
    public boolean canCreate(Long userId) {
        int limit = workspaceLimit(userId);
        if (limit < 0) return true;
        return usedCount(userId) < limit;
    }

    private Integer queryQuota(String planCode, String quotaKey) {
        try {
            SubscriptionPlanQuota row = quotaMapper.selectOne(
                    new LambdaQueryWrapper<SubscriptionPlanQuota>()
                            .eq(SubscriptionPlanQuota::getPlanCode, planCode)
                            .eq(SubscriptionPlanQuota::getQuotaKey, quotaKey)
                            .last("LIMIT 1"));
            return row == null ? null : row.getQuotaValue();
        } catch (Exception e) {
            log.warn("读取配额失败 planCode={} key={}", planCode, quotaKey, e);
            return null;
        }
    }
}

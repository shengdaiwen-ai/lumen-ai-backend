package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lumenai.backend.dto.BillingUsage;
import com.lumenai.backend.dto.CreditsInfoVO;
import com.lumenai.backend.entity.CreditGroupRatio;
import com.lumenai.backend.entity.CreditHold;
import com.lumenai.backend.entity.CreditReward;
import com.lumenai.backend.entity.CreditTransaction;
import com.lumenai.backend.entity.ModelPricing;
import com.lumenai.backend.entity.UserSubscription;
import com.lumenai.backend.mapper.CreditAccountMapper;
import com.lumenai.backend.mapper.CreditGroupRatioMapper;
import com.lumenai.backend.mapper.CreditHoldMapper;
import com.lumenai.backend.mapper.CreditRewardMapper;
import com.lumenai.backend.mapper.CreditTransactionMapper;
import com.lumenai.backend.mapper.ModelPricingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 聊天积分扣费（<b>两阶段</b>）
 *
 * <p>三个动作构成完整闭环，任何一次对话都在这条链上：</p>
 * <ol>
 *   <li><b>预扣</b> {@link #hold}：调模型<b>之前</b>按估算额条件扣减（余额不足直接拒绝），
 *       落一张 {@code credit_holds} 单据；</li>
 *   <li><b>结算</b> {@link #settle}：拿到真实 usage 后重算，<b>多退少补</b>，写消耗流水，
 *       并按「先到期先扣」扣减展示批次；</li>
 *   <li><b>退款</b> {@link #refund} / {@link #sweepExpiredHolds}：请求失败或零产出全额退；
 *       进程崩溃留下的僵尸单由惰性清扫退 —— <b>绝不把用户的钱扣在半路</b>。</li>
 * </ol>
 *
 * <p>为什么不是「先跑完再扣」：余额为 0 的用户也能跑完一次昂贵任务，扣费时扣不动，
 * 平台白送成本。预扣把「能不能付得起」前置到请求发起时。</p>
 *
 * <p>所有价格与倍率都<b>查库</b>（{@code model_pricing} / {@code credit_group_ratio}），
 * 单价属于运营数据，红线 14 禁硬编码 —— 连「未登记模型的兜底价」也在表里（{@code is_fallback}）。</p>
 *
 * <p><b>金额精度：两位小数</b>。积分不是「粒度为 1 的代币」—— 以 pro 价（输入 0.2 积分/千 token）
 * 为例，1 积分≈5,000 输入 token，一次「你好」的真实成本约 0.001 积分。若按整数向上取整，
 * 小额请求要被收 1 积分（最高放大三个数量级）。故落账一律
 * {@code DECIMAL(12,2)} / {@code BigDecimal}，内部按 8 位精度算、落账前
 * {@code HALF_UP} 归到 2 位，一次真实调用的兜底下限是 {@value #MIN_CHARGE_STR} 积分。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatBillingService {

    /** 余额不足的错误码（SSE error 事件 / 前端据此出横幅并引导购买） */
    public static final String NO_CREDITS = "NO_CREDITS";

    /** 预扣单超时分钟数：超过即视为僵尸单退款（远大于一次对话的最长生命周期） */
    private static final int HOLD_TIMEOUT_MINUTES = 10;

    /** 单次清扫最多处理多少张僵尸单（防止一次登录触发大量 DB 写） */
    private static final int SWEEP_BATCH = 50;

    /** 流水标题最大字符数（明细页一行放得下；列本身是 VARCHAR(255)，不靠 DB 兜底） */
    private static final int TITLE_MAX_CHARS = 32;

    /** 落账精度：积分保留两位小数（列均为 DECIMAL(12,2)） */
    private static final int CREDIT_SCALE = 2;

    /** 一次真实调用的最小扣费：0.01 积分（原实现是「至少 1 积分」，对小额请求放大过度） */
    private static final BigDecimal MIN_CHARGE = new BigDecimal("0.01");

    /** 最小扣费的字符串形态（{@code @value} 注解只吃常量表达式） */
    private static final String MIN_CHARGE_STR = "0.01";

    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final int CALC_SCALE = 8;

    private final ModelPricingMapper pricingMapper;
    private final CreditGroupRatioMapper ratioMapper;
    private final CreditHoldMapper holdMapper;
    private final CreditAccountMapper accountMapper;
    private final CreditTransactionMapper transactionMapper;
    private final CreditRewardMapper rewardMapper;
    private final SubscriptionUpgradeService upgradeService;
    private final CreditService creditService;

    // ==================== 计价 ====================

    /**
     * 估算 token：中文/符号混合文本按 4 字符 ≈ 1 token 粗算（与上游的估算口径一致）。
     *
     * <p>只用于<b>预扣</b>：预扣多算了会退、少算了会补，所以粗算不影响最终账，
     * 只影响「能不能发起这次请求」的门槛。</p>
     */
    public static long estimateTokens(long chars) {
        return Math.max(1, (chars + 3) / 4);
    }

    /**
     * 取模型单价（每次查库，运营改价立即生效；按 model_code 唯一键单行读，成本可忽略）。
     *
     * @return 命中的单价行；模型未登记时回落到 {@code is_fallback=1} 的兜底行；
     *         连兜底行都没有（运营误删）则返回 null，调用方按「不扣费 + WARN」处理
     */
    public ModelPricing pricingOf(String modelCode) {
        if (modelCode != null && !modelCode.isBlank()) {
            ModelPricing hit = pricingMapper.selectOne(new LambdaQueryWrapper<ModelPricing>()
                    .eq(ModelPricing::getModelCode, modelCode)
                    .eq(ModelPricing::getStatus, "active")
                    .last("LIMIT 1"));
            if (hit != null) {
                return hit;
            }
            log.warn("模型未登记计价，回落到兜底价 model={}", modelCode);
        }
        ModelPricing fallback = pricingMapper.selectOne(new LambdaQueryWrapper<ModelPricing>()
                .eq(ModelPricing::getIsFallback, 1)
                .eq(ModelPricing::getStatus, "active")
                .last("LIMIT 1"));
        if (fallback == null) {
            log.warn("计价表无兜底行（model_pricing.is_fallback=1 缺失），本次不扣费");
        }
        return fallback;
    }

    /**
     * 分组倍率：{@code plan:<当前套餐>} → {@code channel:<通道>} → {@code default}，先命中即用。
     *
     * @return 倍率；三层都没有（运营清空表）返回 1.0，保持「能正常计费」而不是报错
     */
    public BigDecimal groupRatioOf(Long userId, String channel) {
        List<String> scopes = new ArrayList<>();
        String planCode = activePlanCodeOf(userId);
        if (planCode != null && !planCode.isBlank()) {
            scopes.add("plan:" + planCode);
        }
        if (channel != null && !channel.isBlank()) {
            scopes.add("channel:" + channel);
        }
        scopes.add(CreditGroupRatio.SCOPE_DEFAULT);
        List<CreditGroupRatio> rows = ratioMapper.selectList(new LambdaQueryWrapper<CreditGroupRatio>()
                .in(CreditGroupRatio::getScope, scopes));
        for (String scope : scopes) {
            for (CreditGroupRatio row : rows) {
                if (scope.equals(row.getScope()) && row.getRatio() != null && row.getRatio().signum() >= 0) {
                    return row.getRatio();
                }
            }
        }
        return ONE;
    }

    /** 用户当前生效套餐 code（无订阅返回 null） */
    private String activePlanCodeOf(Long userId) {
        // 复用升级折算里那套「生效订阅」判定（status=active 且未过期），避免两处口径分叉
        UserSubscription active = upgradeService.findActiveSubscription(userId);
        return active == null ? null : active.getPlanCode();
    }

    /**
     * 计价公式（文档 §3.1）。
     *
     * <pre>
     *   raw = (hit × pin × cache_ratio + miss × pin + out × pout) / 1000 × 分组倍率
     *   C   = max(0.01, round(raw, 2))     （免费模型 / 零 token：C = 0）
     * </pre>
     *
     * <p>内部按 {@value #CALC_SCALE} 位算出、落账前 {@code HALF_UP} 归到 2 位小数：
     * 保留高位是为了「单价 × 大 token 数」不丢精度，归到 2 位是因为账本列就是
     * {@code DECIMAL(12,2)} —— 落库值必须与对外展示、与余额扣减完全一致。</p>
     */
    public BigDecimal computeCost(ModelPricing pricing, int hitTokens, int missTokens, int outTokens,
                                  BigDecimal groupRatio) {
        if (pricing == null) {
            return zero();
        }
        if (pricing.getIsFree() != null && pricing.getIsFree() == 1) {
            return zero();
        }
        if (hitTokens <= 0 && missTokens <= 0 && outTokens <= 0) {
            // 没有任何 token 就不是一次可计费的调用（预扣阶段也不会走到这里）
            return zero();
        }
        BigDecimal cacheRatio = pricing.getCacheRatio() == null ? ONE : pricing.getCacheRatio();
        BigDecimal raw = BigDecimal.ZERO;
        if (hitTokens > 0) {
            raw = raw.add(BigDecimal.valueOf(hitTokens)
                    .multiply(pricing.getInputPrice())
                    .multiply(cacheRatio));
        }
        if (missTokens > 0) {
            raw = raw.add(BigDecimal.valueOf(missTokens).multiply(pricing.getInputPrice()));
        }
        if (outTokens > 0) {
            raw = raw.add(BigDecimal.valueOf(outTokens).multiply(pricing.getOutputPrice()));
        }
        BigDecimal credits = raw.divide(THOUSAND, CALC_SCALE, RoundingMode.HALF_UP)
                .multiply(groupRatio == null ? ONE : groupRatio)
                .setScale(CREDIT_SCALE, RoundingMode.HALF_UP);
        // 有 token 就是一次真实调用：极小请求（算下来不足一分）也至少收 0.01 积分，
        // 与「免费模型全程零成本」区分开
        return credits.signum() <= 0 ? MIN_CHARGE : credits;
    }

    /** 两位小数的零（保证对外数值 Scale 一致，避免前端拿到 0 与 0.00 两种形态） */
    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(CREDIT_SCALE, RoundingMode.UNNECESSARY);
    }

    // ==================== 阶段 1：预扣 ====================

    /**
     * 预扣（调模型前调用）。
     *
     * <p>输入按「本次待发文本 chars/4」估，输出按模型配置的 {@code max_output} 上限估 ——
     * 上限估法偏保守（真跑满才花得完），所以正常对话结算时都会退一笔回来。</p>
     *
     * @param requestId 幂等键，同一请求重放不会二次预扣
     */
    @Transactional(rollbackFor = Exception.class)
    public HoldResult hold(Long userId, String requestId, Long conversationId, String channel,
                           String modelCode, long estInputTokens) {
        ModelPricing pricing = pricingOf(modelCode);
        if (pricing == null || (pricing.getIsFree() != null && pricing.getIsFree() == 1)) {
            // 免费模型 / 无计价：不预扣也不结算，全程零成本
            return HoldResult.skipped();
        }
        creditService.ensureAccounts(userId);
        BigDecimal ratio = groupRatioOf(userId, channel);
        int estOutput = pricing.getMaxOutput() == null || pricing.getMaxOutput() <= 0 ? 8192 : pricing.getMaxOutput();
        BigDecimal pre = computeCost(pricing, 0, (int) Math.min(estInputTokens, Integer.MAX_VALUE), estOutput, ratio);

        int affected = accountMapper.deductCredits(userId, CreditService.TYPE_GENERAL, pre);
        if (affected == 0) {
            BigDecimal balance = creditService.generalBalanceOf(userId);
            log.info("预扣被拒（余额不足）userId={} 需要={} 现有={}", userId, pre, balance);
            return HoldResult.insufficient(pre, balance);
        }

        CreditHold hold = new CreditHold();
        hold.setRequestId(requestId);
        hold.setUserId(userId);
        hold.setChannel(channel == null ? CreditHold.CHANNEL_WEB : channel);
        hold.setConversationId(conversationId);
        hold.setModelCode(pricing.getModelCode());
        hold.setEstimatedCost(pre);
        hold.setStatus(CreditHold.STATUS_HELD);
        hold.setCreatedAt(LocalDateTime.now());
        holdMapper.insert(hold);

        BigDecimal balanceAfter = creditService.generalBalanceOf(userId);
        log.info("预扣成功 userId={} channel={} model={} preConsumed={} balanceAfter={} requestId={}",
                userId, channel, pricing.getModelCode(), pre, balanceAfter, requestId);
        return HoldResult.ok(hold.getId(), pre, balanceAfter);
    }

    /** 生成本次请求的幂等键 */
    public static String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ==================== 阶段 2：结算（多退少补） ====================

    /**
     * 结算（拿到真实 usage 后调用，幂等）。
     *
     * <p>金额恒等式 {@code cost + refunded = preConsumed} 由本方法保证：先按预扣单抢占状态
     * （抢不到 = 已被结算或已退款，直接跳过），再算实际值，多的退、少的补。</p>
     *
     * @param usage 本次用量快照（含缓存命中拆分）
     * @return 结算结果；未发生扣费（免费模型 / 无预扣单 / 已结算过）返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public CreditsInfoVO settle(Long userId, String requestId, String modelCode, BillingUsage usage,
                                Long messageId, String recordTitle) {
        CreditHold hold = holdMapper.selectOne(new LambdaQueryWrapper<CreditHold>()
                .eq(CreditHold::getRequestId, requestId)
                .last("LIMIT 1"));
        if (hold == null) {
            // 免费模型未预扣，或预扣阶段就被拒绝 —— 都无需结算
            return null;
        }
        int claimed = claim(hold.getId(), CreditHold.STATUS_SETTLED, null, LocalDateTime.now());
        if (claimed == 0) {
            log.info("预扣单已被结算/退款，跳过重复结算 requestId={}", requestId);
            return null;
        }

        ModelPricing pricing = pricingOf(modelCode != null ? modelCode : hold.getModelCode());
        BigDecimal ratio = groupRatioOf(userId, hold.getChannel());
        int hit = usage == null ? 0 : usage.getCacheHitTokens();
        int miss = usage == null ? 0 : usage.missTokens();
        int out = usage == null ? 0 : usage.getOutputTokens();
        boolean estimated = usage != null && usage.isEstimated();
        BigDecimal cost = computeCost(pricing, hit, miss, out, ratio);
        BigDecimal preConsumed = hold.getEstimatedCost() == null ? zero() : hold.getEstimatedCost();

        // 多退少补：预扣额与实际值的差额，两个方向都用 addBalance（传负数即减）
        BigDecimal delta = cost.subtract(preConsumed);
        if (delta.signum() != 0) {
            if (accountMapper.addBalance(userId, CreditService.TYPE_GENERAL, delta.negate()) == 0) {
                log.warn("结算差额调整失败（账户不存在）userId={} delta={}", userId, delta);
            }
        }
        holdMapper.update(null, new LambdaUpdateWrapper<CreditHold>()
                .eq(CreditHold::getId, hold.getId())
                .set(CreditHold::getActualCost, cost));

        if (cost.signum() > 0) {
            CreditTransaction tx = new CreditTransaction();
            tx.setUserId(userId);
            tx.setCreditType(CreditService.TYPE_GENERAL);
            // 标题只承载「这是哪一轮提问」；估算口径走独立列，不塞进业务文本。
            // （历史事故：前缀写在标题里，明细页每一行都带 [估算]，用户以为记录坏了）
            tx.setRecordTitle(titleOf(recordTitle));
            tx.setEstimated(estimated);
            tx.setModelName(modelCode != null && !modelCode.isBlank() ? modelCode : hold.getModelCode());
            tx.setProduct("Lumen AI");
            tx.setCost(cost);
            tx.setType(CreditTransaction.TYPE_CONSUME);
            tx.setMessageId(messageId);
            tx.setHoldId(hold.getId());
            tx.setInputTokens(hit + miss);
            tx.setOutputTokens(out);
            tx.setCreatedAt(LocalDateTime.now());
            transactionMapper.insert(tx);
            // 批次是「展示视图」，扣不齐不影响权威源一致性，因此失败只告警
            decrementRewardBatches(userId, cost);
        }

        BigDecimal balanceAfter = creditService.generalBalanceOf(userId);
        log.info("结算完成 userId={} requestId={} 预扣={} 实际={} 退回={} tokens(hit/miss/out)={}/{}/{} estimated={}",
                userId, requestId, preConsumed, cost, preConsumed.subtract(cost),
                hit, miss, out, estimated);
        return CreditsInfoVO.builder()
                .cost(cost)
                .preConsumed(preConsumed)
                .refunded(preConsumed.subtract(cost))
                .balanceAfter(balanceAfter)
                .inputTokens(hit + miss)
                .outputTokens(out)
                .estimated(estimated)
                .build();
    }

    // ==================== 退款 ====================

    /**
     * 全额退款（请求失败、零产出、异常路径）。
     *
     * <p>只退预扣额，不写消耗流水 —— 用户什么都没得到，账上也不该留下消耗记录。</p>
     */
    public void refund(Long userId, String requestId, String reason) {
        try {
            CreditHold hold = holdMapper.selectOne(new LambdaQueryWrapper<CreditHold>()
                    .eq(CreditHold::getRequestId, requestId)
                    .last("LIMIT 1"));
            if (hold == null) {
                return;
            }
            if (claim(hold.getId(), CreditHold.STATUS_REFUNDED, BigDecimal.ZERO, LocalDateTime.now()) == 0) {
                return;
            }
            BigDecimal amount = hold.getEstimatedCost() == null ? zero() : hold.getEstimatedCost();
            accountMapper.addBalance(userId, CreditService.TYPE_GENERAL, amount);
            log.info("预扣已退款 userId={} requestId={} amount={} reason={}",
                    userId, requestId, amount, reason);
        } catch (Exception e) {
            log.warn("退款失败 userId={} requestId={} reason={}", userId, requestId, reason, e);
        }
    }

    /**
     * 惰性清扫僵尸单：进程崩溃 / 结算异常会让预扣单永远停在 {@code held}（钱被扣着没结算）。
     *
     * <p>与积分按月发放同一套思路 —— <b>不引定时任务</b>，挂在用户必然会经过的入口
     * （发消息、登录取用户信息、看积分）上顺手扫一次，超过 {@value #HOLD_TIMEOUT_MINUTES}
     * 分钟的单据一律退款。真实结算不会误伤：一次对话的生命周期远短于这个阈值。</p>
     *
     * @return 本次清扫退款的单数
     */
    @Transactional(rollbackFor = Exception.class)
    public int sweepExpiredHolds(Long userId) {
        if (userId == null) {
            return 0;
        }
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(HOLD_TIMEOUT_MINUTES);
        List<CreditHold> stale = holdMapper.selectList(new LambdaQueryWrapper<CreditHold>()
                .eq(CreditHold::getUserId, userId)
                .eq(CreditHold::getStatus, CreditHold.STATUS_HELD)
                .lt(CreditHold::getCreatedAt, deadline)
                .orderByAsc(CreditHold::getId)
                .last("LIMIT " + SWEEP_BATCH));
        BigDecimal refundedAmount = BigDecimal.ZERO;
        int refunded = 0;
        for (CreditHold hold : stale) {
            if (claim(hold.getId(), CreditHold.STATUS_EXPIRED, null, LocalDateTime.now()) == 0) {
                continue;
            }
            BigDecimal amount = hold.getEstimatedCost() == null ? zero() : hold.getEstimatedCost();
            accountMapper.addBalance(userId, CreditService.TYPE_GENERAL, amount);
            refundedAmount = refundedAmount.add(amount);
            refunded++;
        }
        if (refunded > 0) {
            log.warn("清扫僵尸预扣单并退款 userId={} 单数={} 合计积分={}", userId, refunded, refundedAmount);
        }
        return refunded;
    }

    // ==================== 内部工具 ====================

    /**
     * 抢占预扣单状态：只允许 {@code held → 目标状态} 的条件更新。
     *
     * @return 影响行数；0 = 已被别处处理过（幂等挡板）
     */
    private int claim(Long holdId, String toStatus, BigDecimal actualCost, LocalDateTime at) {
        LambdaUpdateWrapper<CreditHold> update = new LambdaUpdateWrapper<CreditHold>()
                .eq(CreditHold::getId, holdId)
                .eq(CreditHold::getStatus, CreditHold.STATUS_HELD)
                .set(CreditHold::getStatus, toStatus)
                .set(CreditHold::getSettledAt, at);
        if (actualCost != null) {
            update.set(CreditHold::getActualCost, actualCost);
        }
        return holdMapper.update(null, update);
    }

    /**
     * 按「先到期先扣」扣减展示批次（credit_rewards.remaining）。
     *
     * <p>权威源是 {@code credit_accounts}，批次只是明细视图 —— 所以这里扣不齐只告警，
     * 不抛异常、不回滚主事务（绝不让展示层的问题影响扣费结果）。</p>
     */
    private void decrementRewardBatches(Long userId, BigDecimal cost) {
        try {
            List<CreditReward> rewards = rewardMapper.selectList(new LambdaQueryWrapper<CreditReward>()
                    .eq(CreditReward::getUserId, userId)
                    .eq(CreditReward::getCreditType, CreditService.TYPE_GENERAL)
                    .gt(CreditReward::getRemaining, BigDecimal.ZERO)
                    .last("ORDER BY expires_at IS NULL, expires_at ASC, id ASC"));
            BigDecimal left = cost;
            for (CreditReward reward : rewards) {
                if (left.signum() <= 0) {
                    break;
                }
                // 每个批次最多扣到剩余为 0，多出的部分顺延给下一个批次
                BigDecimal take = left.min(reward.getRemaining());
                if (take.signum() <= 0) {
                    continue;
                }
                if (rewardMapper.reclaim(reward.getId(), take) > 0) {
                    left = left.subtract(take);
                }
            }
            if (left.signum() > 0) {
                log.warn("批次展示扣减未扣齐 userId={} 余额扣减={} 批次未扣={}", userId, cost, left);
            }
        } catch (Exception e) {
            log.warn("批次展示扣减异常（不影响账户余额）userId={}", userId, e);
        }
    }

    /**
     * 流水标题的落库口径：多行折叠成一行、去首尾空白、超长截断、空值兜底。
     *
     * <p>调用方只管把「本轮用户提问」原文交进来（Web 聊天是消息内容，办公助理带「办公助理·」前缀），
     * 清洗规则集中在这里，避免各入口各写一套导致同一列出现不同形态。</p>
     */
    private String titleOf(String recordTitle) {
        String title = recordTitle == null ? "" : recordTitle.replaceAll("\\s+", " ").trim();
        if (title.isEmpty()) {
            return "对话消耗";
        }
        return title.length() > TITLE_MAX_CHARS ? title.substring(0, TITLE_MAX_CHARS) : title;
    }

    /**
     * 预扣结果
     *
     * @param free         免费模型 / 无计价 → 全程零成本，不结算
     * @param insufficient 余额不足预扣额 → 本次请求必须拒绝
     * @param required     本次预扣所需积分（拒绝时用于提示「应需 / 现有」）
     */
    public record HoldResult(boolean free, boolean insufficient, Long holdId, BigDecimal preConsumed,
                             BigDecimal balanceAfter, BigDecimal required) {

        /** 免费模型 / 无计价：不预扣，也不结算 */
        public static HoldResult skipped() {
            return new HoldResult(true, false, null, BigDecimal.ZERO, null, BigDecimal.ZERO);
        }

        /** 余额不足预扣额（拒绝本次请求） */
        public static HoldResult insufficient(BigDecimal required, BigDecimal balance) {
            return new HoldResult(false, true, null, BigDecimal.ZERO, balance, required);
        }

        /** 预扣成功 */
        public static HoldResult ok(Long holdId, BigDecimal preConsumed, BigDecimal balanceAfter) {
            return new HoldResult(false, false, holdId, preConsumed, balanceAfter, preConsumed);
        }

        /** 是否真的扣了钱（有待结算的预扣单） */
        public boolean billed() {
            return holdId != null;
        }
    }
}

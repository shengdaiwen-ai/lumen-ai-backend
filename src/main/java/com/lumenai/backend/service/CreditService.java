package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.common.exception.BusinessException;
import com.lumenai.backend.dto.CreditOverviewVO;
import com.lumenai.backend.dto.CreditRewardVO;
import com.lumenai.backend.dto.CreditUsageVO;
import com.lumenai.backend.entity.CreditAccount;
import com.lumenai.backend.entity.CreditReward;
import com.lumenai.backend.entity.CreditTransaction;
import com.lumenai.backend.mapper.CreditAccountMapper;
import com.lumenai.backend.mapper.CreditRewardMapper;
import com.lumenai.backend.mapper.CreditTransactionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 积分服务
 *
 * <p>数据来自 credit_accounts / credit_rewards / credit_transactions 三张表，
 * 账户余额、奖励批次、消耗流水均由数据驱动，**这里是积分余额的唯一权威源**
 * （{@code users.credits} 遗留列已于 2026-09-17 删除）。</p>
 *
 * <p>账户采用**懒初始化**：首次访问时若不存在则从 0 补建，
 * 唯一键 uk_user_type 兜底并发重复插入。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditService {

    /** 通用积分（当前唯一在用的积分类型；后续扩展类型如平台奖励积分在此加常量并同步 ensureAccounts） */
    public static final String TYPE_GENERAL = "general";

    /** 账户默认产品端 */
    private static final String DEFAULT_PRODUCT = "Lumen AI";

    /** 用量明细默认每页条数 */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** pageSize 上限（规范 §9.4：必须封顶，禁止前端传任意大值） */
    private static final int MAX_PAGE_SIZE = 100;

    /** 套餐赠送批次的展示顺序：排在最前（运营类奖励现有 1~4）。发放侧在 CreditGrantService */
    public static final int REWARD_SORT_SUBSCRIPTION = 0;

    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CreditAccountMapper accountMapper;
    private final CreditRewardMapper rewardMapper;
    private final CreditTransactionMapper transactionMapper;

    /**
     * 通用积分余额（只读，无副作用）。
     *
     * <p><b>积分余额的唯一权威来源是 {@code credit_accounts}</b>。账户不存在（刚注册、从未访问过积分页）
     * 时返回 0 —— {@code users.credits} 历史遗留列已于 2026-09-17 删除，不再保留任何回落口子。</p>
     *
     * <p>此处刻意<b>不</b>建账户：读接口不该有写副作用，账户由 {@link #ensureAccounts} 在真正需要时创建。</p>
     */
    public BigDecimal generalBalanceOf(Long userId) {
        CreditAccount account = accountMapper.selectOne(
                new LambdaQueryWrapper<CreditAccount>()
                        .eq(CreditAccount::getUserId, userId)
                        .eq(CreditAccount::getCreditType, TYPE_GENERAL)
                        .last("LIMIT 1")
        );
        return (account == null || account.getBalance() == null)
                ? BigDecimal.ZERO
                : account.getBalance();
    }

    /**
     * 升级时回收「旧套餐当月批次」的未消耗部分（写操作，供下单事务调用）。
     *
     * <p><b>回收比例必须与折算比例完全相同（同一个 {@code k}）</b>：钱按 {@code k} 退了当月，
     * 积分就按 {@code k} 收当月，同一份权益只结算一次。若改成「未用完的全收走」，
     * 会出现「退 43% 的钱、却收走 80% 的积分」，还会推出「越早升级越亏积分」的反直觉结论。</p>
     *
     * <p>三件事必须同事务：账户扣减、批次剩余扣减、负向流水。任何一步失败都抛异常回滚整笔升级 ——
     * 绝不允许「钱退了、积分没收回」的半结算状态。</p>
     *
     * @param rewardId 旧订阅当月批次的 {@code credit_rewards.id}；
     *                 <b>为 null 时跳过批次扣减，只记日志</b>（存量数据没有批次归属，
     *                 这时无法核算应回收多少，宁可让利也不凭空扣用户的积分）
     * @param amount   回收量（正数，内部取负入账）
     */
    public void reclaimSubscriptionCredits(Long userId, Long rewardId, BigDecimal amount, String title) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        BigDecimal negative = amount.negate();
        if (rewardId != null && rewardMapper.reclaim(rewardId, amount) == 0) {
            throw new BusinessException("当月积分批次剩余不足，本次升级已取消，请重试");
        }
        if (rewardId == null) {
            log.warn("旧订阅当月批次缺失，本次升级不回收积分 userId={} amount={}", userId, amount);
        }
        if (accountMapper.addBalance(userId, TYPE_GENERAL, negative) == 0) {
            throw new BusinessException("积分账户异常，本次升级已取消，请重试");
        }
        CreditTransaction tx = new CreditTransaction();
        tx.setUserId(userId);
        tx.setCreditType(TYPE_GENERAL);
        tx.setRecordTitle(title);
        tx.setModelName("subscription");
        tx.setProduct(DEFAULT_PRODUCT);
        tx.setCost(negative);
        tx.setType(CreditTransaction.TYPE_RECLAIM);
        transactionMapper.insert(tx);
    }

    /** 积分总览。只读，幂等。 */
    public CreditOverviewVO getOverview() {
        Long userId = UserContext.get();
        ensureAccounts(userId);

        List<CreditAccount> accounts = accountMapper.selectList(
                new LambdaQueryWrapper<CreditAccount>()
                        .eq(CreditAccount::getUserId, userId)
                        .orderByAsc(CreditAccount::getSortOrder)
                        .orderByAsc(CreditAccount::getId)
        );

        BigDecimal total = accounts.stream()
                .map(a -> a.getBalance() == null ? BigDecimal.ZERO : a.getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return CreditOverviewVO.builder()
                .totalAvailable(total)
                .accounts(accounts.stream()
                        .map(a -> CreditOverviewVO.AccountItem.builder()
                                .type(a.getCreditType())
                                .product(a.getProduct())
                                .balance(a.getBalance() == null ? BigDecimal.ZERO : a.getBalance())
                                .build())
                        .toList())
                .build();
    }

    /** 奖励积分列表。只读，幂等。 */
    public List<CreditRewardVO> getRewards() {
        Long userId = UserContext.get();
        return rewardMapper.selectList(
                        new LambdaQueryWrapper<CreditReward>()
                                .eq(CreditReward::getUserId, userId)
                                .orderByAsc(CreditReward::getSortOrder)
                                .orderByAsc(CreditReward::getId)
                ).stream()
                .map(r -> CreditRewardVO.builder()
                        .id(r.getId())
                        .title(r.getTitle())
                        .type(r.getCreditType())
                        .remaining(zeroIfNull(r.getRemaining()))
                        .total(zeroIfNull(r.getTotal()))
                        .expiresAt(r.getExpiresAt() == null ? null : r.getExpiresAt().format(MINUTE_FORMAT))
                        .batchCount(r.getBatchCount() == null ? 1 : r.getBatchCount())
                        .build())
                .toList();
    }

    /**
     * 用量明细分页查询。只读，幂等。
     *
     * @param range today(今天) / 7d(近7天) / 30d(近30天)；其余或为空表示不限时间
     */
    public CreditUsageVO getUsage(Integer pageNum, Integer pageSize, String range) {
        Long userId = UserContext.get();
        int pn = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int ps = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);

        String normalized = range == null ? "" : range.trim().toLowerCase();
        LocalDateTime start = rangeStart(normalized);

        Page<CreditTransaction> page = transactionMapper.selectPage(
                new Page<>(pn, ps),
                new LambdaQueryWrapper<CreditTransaction>()
                        .eq(CreditTransaction::getUserId, userId)
                        .ge(start != null, CreditTransaction::getCreatedAt, start)
                        .orderByDesc(CreditTransaction::getCreatedAt)
                        .orderByDesc(CreditTransaction::getId)
        );

        return CreditUsageVO.builder()
                .total(page.getTotal())
                .pageNum(page.getCurrent())
                .pageSize(page.getSize())
                .totalPages(page.getPages())
                .startDate(start == null ? null : start.format(DAY_FORMAT))
                .endDate(start == null ? null : LocalDate.now().format(DAY_FORMAT))
                .records(page.getRecords().stream()
                        .map(t -> CreditUsageVO.UsageRecord.builder()
                                .id(t.getId())
                                .time(t.getCreatedAt() == null ? null : t.getCreatedAt().format(MINUTE_FORMAT))
                                .record(t.getRecordTitle())
                                .model(t.getModelName())
                                .product(t.getProduct())
                                .cost(zeroIfNull(t.getCost()))
                                .inputTokens(t.getInputTokens())
                                .outputTokens(t.getOutputTokens())
                                .estimated(t.getEstimated())
                                .build())
                        .toList())
                .build();
    }

    /**
     * 统计区间起点。7天/30天按「含今天在内的 N 个自然日」计算，
     * 这样返回给前端的起止日期是一个完整自然日区间，便于展示。
     */
    private LocalDateTime rangeStart(String range) {
        LocalDateTime now = LocalDateTime.now();
        return switch (range) {
            case "today" -> LocalDate.now().atStartOfDay();
            case "7d" -> now.minusDays(6).toLocalDate().atStartOfDay();
            case "30d" -> now.minusDays(29).toLocalDate().atStartOfDay();
            default -> null;
        };
    }

    /**
     * 账户懒创建（幂等）。新账户一律从 0 起 —— users.credits 遗留列已删除，没有历史余额可继承。
     *
     * <p>开放为 public 是因为「按月发放」的入账在 {@code CreditGrantService} 里，
     * 它复用本方法而不是自己再建一套账户初始化 —— 账户的建表规则必须单点维护。</p>
     */
    public void ensureAccounts(Long userId) {
        createAccountIfAbsent(userId, TYPE_GENERAL, BigDecimal.ZERO, 1);
    }

    private void createAccountIfAbsent(Long userId, String type, BigDecimal balance, int sortOrder) {
        Long exists = accountMapper.selectCount(
                new LambdaQueryWrapper<CreditAccount>()
                        .eq(CreditAccount::getUserId, userId)
                        .eq(CreditAccount::getCreditType, type)
        );
        if (exists != null && exists > 0) {
            return;
        }
        CreditAccount account = new CreditAccount();
        account.setUserId(userId);
        account.setCreditType(type);
        account.setProduct(DEFAULT_PRODUCT);
        account.setBalance(balance);
        account.setSortOrder(sortOrder);
        try {
            accountMapper.insert(account);
        } catch (Exception e) {
            // 并发下唯一键冲突：说明另一个请求已建好，忽略即可
            log.debug("积分账户已存在，跳过创建 userId={} type={}", userId, type);
        }
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

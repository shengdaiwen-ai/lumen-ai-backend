package com.lumenai.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

/**
 * 启动时执行数据库结构迁移（幂等）
 * <p>
 * 当前迁移：为 schedule_runs 表添加 trigger_type 字段，区分手动/自动执行；
 * 订阅套餐三张表的建表与初始数据；积分三张表；用户已购订阅表；订阅订单与余额流水表；
 * 以及删除 users 表的历史遗留列 credits / plan；
 * 兜底把全库表排序规则统一到 utf8mb4_unicode_ci。
 */
@Slf4j
@Component
@Order(1)
public class DbMigrationRunner implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public DbMigrationRunner(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) {
        addTriggerTypeColumn();
        addSessionIdColumn();
        addMessagesCancelledColumn();
        // iLink Bot 架构迁移
        addChannelAccountsIlinkColumns();
        addChannelMessagesIlinkColumns();
        // 用户账户余额（购买套餐可用）
        addColumnIfNotExists("users", "balance",
                "DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '账户余额（元），可用于购买套餐'", "password");
        // 订阅套餐
        createSubscriptionTables();
        // 套餐每月发放积分（补列 + 存量回填）
        addPlanMonthlyCredits();
        // 积分账户 / 奖励积分 / 积分流水
        createCreditTables();
        // 用户已购订阅
        createUserSubscriptionTable();
        // 计费周期时长（月）：补列 + 回填年付周期
        addBillingPeriodMonths();
        // 订阅订单 + 余额流水
        createBalanceOrderTables();
        // 删除 users 的历史遗留列（credits / plan）—— 必须排在积分账户与订阅表建好之后
        dropLegacyUserColumns();
        // 订阅升级（差额抵扣）+ 积分按月发放 —— 必须排在订阅/订单/积分表建好之后
        upgradeSubscriptionTables();
        // 聊天积分消耗（两阶段扣费）：计价表 / 预扣单 / 分组倍率 + 流水扩列
        chatBillingTables();
        // 工作空间（选择工作空间）：元数据表 + 套餐配额表 + sessions 关联列
        workspaceTables();
        // 权限档位：四档 → 两档（default / full）的取值迁移（2026-09-22）
        permissionModeTwoTier();
        // 废弃对象清理（2026-09-22）：从未上线或已被取代的表/列，从库中移除
        deprecatedCleanup();
        // 审计中心（P4，2026-09-22）：沙箱拦截/放行/审批决定的上报落库表
        createAuditTable();
        addAuditIsolationColumn();
        // 字符集/排序规则一致性兜底 —— 必须排在所有建表之后
        ensureUtf8mb4Collation();
    }

    private void addTriggerTypeColumn() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'schedule_runs' AND COLUMN_NAME = 'trigger_type'",
                    Integer.class
            );
            if (count != null && count > 0) {
                log.debug("schedule_runs.trigger_type 已存在，跳过迁移");
                return;
            }
            jdbcTemplate.execute(
                    "ALTER TABLE schedule_runs " +
                            "ADD COLUMN trigger_type VARCHAR(16) NOT NULL DEFAULT 'manual' COMMENT 'manual(手动立即执行) / auto(定时自动执行)' AFTER status"
            );
            jdbcTemplate.execute(
                    "ALTER TABLE schedule_runs ADD INDEX idx_trigger_type (trigger_type)"
            );
            log.info("迁移完成：schedule_runs.trigger_type 字段已添加");
        } catch (Exception e) {
            log.warn("schedule_runs.trigger_type 迁移失败（可能已存在）: {}", e.getMessage());
        }
    }

    private void addSessionIdColumn() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'schedule_runs' AND COLUMN_NAME = 'session_id'",
                    Integer.class
            );
            if (count != null && count > 0) {
                log.debug("schedule_runs.session_id 已存在，跳过迁移");
                return;
            }
            jdbcTemplate.execute(
                    "ALTER TABLE schedule_runs " +
                            "ADD COLUMN session_id BIGINT NULL DEFAULT NULL COMMENT '关联会话ID' AFTER user_id"
            );
            jdbcTemplate.execute(
                    "ALTER TABLE schedule_runs ADD INDEX idx_session_id (session_id)"
            );
            log.info("迁移完成：schedule_runs.session_id 字段已添加");
        } catch (Exception e) {
            log.warn("schedule_runs.session_id 迁移失败（可能已存在）: {}", e.getMessage());
        }
    }

    private void addMessagesCancelledColumn() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'messages' AND COLUMN_NAME = 'cancelled'",
                    Integer.class
            );
            if (count != null && count > 0) {
                log.debug("messages.cancelled 已存在，跳过迁移");
                return;
            }
            jdbcTemplate.execute(
                    "ALTER TABLE messages ADD COLUMN cancelled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否被用户手动终止'"
            );
            log.info("迁移完成：messages.cancelled 字段已添加");
        } catch (Exception e) {
            log.warn("messages.cancelled 迁移失败（可能已存在）: {}", e.getMessage());
        }
    }

    private void addChannelAccountsIlinkColumns() {
        addColumnIfNotExists("channel_accounts", "bot_token",
                "TEXT NULL COMMENT 'iLink bot_token（Bearer）'", "bind_status");
        addColumnIfNotExists("channel_accounts", "ilink_bot_id",
                "VARCHAR(128) NULL COMMENT 'iLink 返回的 bot_id'", "bot_token");
        addColumnIfNotExists("channel_accounts", "ilink_user_id",
                "VARCHAR(128) NULL COMMENT 'iLink 返回的 user_id'", "ilink_bot_id");
        addColumnIfNotExists("channel_accounts", "base_url",
                "VARCHAR(256) NULL COMMENT 'iLink baseurl'", "ilink_user_id");
        addColumnIfNotExists("channel_accounts", "qr_img_content",
                "VARCHAR(512) NULL COMMENT '二维码URL（liteapp.weixin.qq.com/q/...）'", "qr_code");
    }

    private void addChannelMessagesIlinkColumns() {
        addColumnIfNotExists("channel_messages", "ilink_msg_id",
                "VARCHAR(128) NULL COMMENT 'iLink 侧消息 ID（去重用）'", "media_url");
        addColumnIfNotExists("channel_messages", "context_token",
                "VARCHAR(256) NULL COMMENT 'iLink context_token（回复用）'", "ilink_msg_id");
    }

    /**
     * 订阅套餐三张表：不存在时执行 db/migration_subscription_plans.sql
     * （建表 + 初始套餐数据；脚本内 DDL 为 IF NOT EXISTS、数据为 INSERT IGNORE，可重复执行）。
     */
    private void createSubscriptionTables() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'subscription_plans'",
                    Integer.class
            );
            if (count != null && count > 0) {
                log.debug("subscription_plans 已存在，跳过迁移");
                return;
            }
            try (Connection conn = dataSource.getConnection()) {
                runScript(conn, "db/migration_subscription_plans.sql");
            }
            log.info("迁移完成：订阅套餐表已创建并写入初始数据");
        } catch (Exception e) {
            log.warn("订阅套餐表迁移失败: {}", e.getMessage());
        }
    }

    /**
     * 积分三张表：不存在时执行 db/migration_credits.sql
     * （建表 + 历史余额回填 + 演示数据；脚本内 DDL 为 IF NOT EXISTS、
     * 数据插入均带 NOT EXISTS 判重，可重复执行且不会覆盖运营调整过的数据）。
     */
    private void createCreditTables() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'credit_accounts'",
                    Integer.class
            );
            if (count != null && count > 0) {
                log.debug("credit_accounts 已存在，跳过迁移");
                return;
            }
            try (Connection conn = dataSource.getConnection()) {
                runScript(conn, "db/migration_credits.sql");
            }
            log.info("迁移完成：积分账户表已创建并写入初始数据");
        } catch (Exception e) {
            log.warn("积分表迁移失败: {}", e.getMessage());
        }
    }

    /**
     * 用户已购订阅表：不存在时执行 db/migration_user_subscriptions.sql。
     * <p>脚本只建表、不写种子数据 —— 已购记录只能由真实支付/开通流程写入。</p>
     */
    private void createUserSubscriptionTable() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_subscriptions'",
                    Integer.class
            );
            if (count != null && count > 0) {
                log.debug("user_subscriptions 已存在，跳过迁移");
                return;
            }
            try (Connection conn = dataSource.getConnection()) {
                runScript(conn, "db/migration_user_subscriptions.sql");
            }
            log.info("迁移完成：用户已购订阅表已创建");
        } catch (Exception e) {
            log.warn("用户已购订阅表迁移失败: {}", e.getMessage());
        }
    }

    /**
     * 订阅订单 + 余额流水两张表：不存在时执行 db/migration_balance_orders.sql。
     * <p>脚本只建表、不写种子数据 —— 订单与流水只能由真实支付动作产生。</p>
     */
    private void createBalanceOrderTables() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'subscription_orders'",
                    Integer.class
            );
            if (count != null && count > 0) {
                log.debug("subscription_orders 已存在，跳过迁移");
                return;
            }
            try (Connection conn = dataSource.getConnection()) {
                runScript(conn, "db/migration_balance_orders.sql");
            }
            log.info("迁移完成：订阅订单表与余额流水表已创建");
        } catch (Exception e) {
            log.warn("订阅订单表迁移失败: {}", e.getMessage());
        }
    }

    /**
     * 计费周期时长列 subscription_plan_billings.period_months：
     * 下单算到期时间靠它（<b>不能用 unit 的 "/月" / "/年" 显示文案做判断</b>）。
     * <p>只在「本次真的补了这一列」时回填年付周期为 12 —— 全新建表时种子数据已带正确值，
     * 补列失败时也不回填，避免拿默认值 1 覆盖运营改过的数据。</p>
     */
    private void addBillingPeriodMonths() {
        boolean added = addColumnIfNotExists("subscription_plan_billings", "period_months",
                "INT NOT NULL DEFAULT 1 COMMENT '计费周期时长（月）：1=月付，12=年付。订阅到期时间 = 起始时间 + 本值'",
                "unit");
        if (!added) {
            return;
        }
        try {
            int rows = jdbcTemplate.update(
                    "UPDATE subscription_plan_billings SET period_months = 12 WHERE code LIKE 'yearly%'");
            log.info("迁移完成：计费周期时长已回填，年付 {} 条", rows);
        } catch (Exception e) {
            log.warn("计费周期时长回填失败: {}", e.getMessage());
        }
    }

    /**
     * 套餐每月发放积分列 subscription_plans.monthly_credits：
     * 下单时按「每月积分 × 周期月数」发放（<b>不能去解析权益文案里的数字</b>，
     * 文案随时会被运营改写，靠正则抠数字迟早出错）。
     * <p>只在「本次真的补了这一列」时回填 —— 全新建表时种子数据已带正确值；
     * 顺带把仍停留在旧口径的标准版描述改成与新额度一致（只在文案仍是旧值时更新，
     * 不覆盖运营改过的新文案）。</p>
     */
    private void addPlanMonthlyCredits() {
        boolean added = addColumnIfNotExists("subscription_plans", "monthly_credits",
                "INT NOT NULL DEFAULT 0 COMMENT '每月发放积分；购买时按周期月数一次性发放'",
                "description");
        if (!added) {
            return;
        }
        try {
            int rows = jdbcTemplate.update(
                    "UPDATE subscription_plans SET monthly_credits = CASE code " +
                            "WHEN 'standard' THEN 5000 WHEN 'premium' THEN 9000 WHEN 'flagship' THEN 50000 ELSE 0 END");
            log.info("迁移完成：套餐每月积分已回填 {} 条", rows);
            int fixed = jdbcTemplate.update(
                    "UPDATE subscription_plans SET description = '5,000 积分，覆盖日常所需' " +
                            "WHERE code = 'standard' AND description = '4,000 积分，覆盖日常所需'");
            if (fixed > 0) {
                log.info("迁移完成：标准版描述口径已与积分额度对齐（4,000 → 5,000）");
            }
        } catch (Exception e) {
            log.warn("套餐每月积分回填失败: {}", e.getMessage());
        }
    }

    /**
     * 删除 users 表的历史遗留列（2026-09-17）：
     * <ul>
     *   <li>{@code credits} —— 积分余额的权威源改为 {@code credit_accounts}。删列前先把仍留在该列上的
     *       存量余额并入通用积分账户；<b>幂等</b>：只对「尚无通用账户」的用户执行，合并完同一次调用里
     *       就删列，列不存在时整段跳过，不可能重复累加。合并失败则本次不删列 —— 宁可留个冗余列，
     *       也不能丢余额。</li>
     *   <li>{@code plan} —— 套餐的事实源是 {@code user_subscriptions}。该列只在注册时写死一次 'free'，
     *       购买套餐后从不更新，长期与实际订阅相矛盾（库里已有 standard 生效订阅的用户仍写着 'free'），
     *       无任何保留价值。</li>
     * </ul>
     * <p>调用顺序有要求：必须排在 {@code createCreditTables()} 与 {@code createUserSubscriptionTable()}
     * 之后（合并目标表得先存在）。</p>
     */
    private void dropLegacyUserColumns() {
        if (columnExists("users", "credits")) {
            try {
                int rows = jdbcTemplate.update(
                        "INSERT INTO credit_accounts (user_id, credit_type, product, balance, sort_order) " +
                                "SELECT u.id, 'general', 'Lumen AI', GREATEST(u.credits, 0), 1 FROM users u " +
                                "WHERE NOT EXISTS (SELECT 1 FROM credit_accounts a " +
                                "WHERE a.user_id = u.id AND a.credit_type = 'general')"
                );
                if (rows > 0) {
                    log.info("迁移完成：{} 个用户的 users.credits 已并入通用积分账户", rows);
                }
            } catch (Exception e) {
                log.warn("users.credits 并入积分账户失败，为避免丢数据本次不删列: {}", e.getMessage());
                return;
            }
        }
        dropColumnIfExists("users", "credits");
        dropColumnIfExists("users", "plan");
    }

    /**
     * 订阅升级（差额抵扣）+ 积分按月发放 的结构迁移（2026-09-18）。
     *
     * <p>两件事是一体的：<b>积分按月发放是差价抵扣的前提</b> —— 未来月份的积分从未发出，
     * 「退未生效月的钱」才不构成双重补偿（否则用户既拿退款又留积分）。</p>
     *
     * <p>为什么升级抵扣的基数用「净支付额」而不是套餐标价：标价作基数可以被反复套利 ——
     * 用标价算出的剩余价值去抵新套餐价，等于平台额外补贴。</p>
     *
     * <p>调用顺序有要求：必须排在订阅表 / 订单表 / 积分表建好之后（要 ALTER 与回填它们）。</p>
     */
    private void upgradeSubscriptionTables() {
        // 1) 积分发放计划表（按月发放的幂等账本）
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'subscription_credit_grants'",
                    Integer.class
            );
            if (count == null || count == 0) {
                try (Connection conn = dataSource.getConnection()) {
                    runScript(conn, "db/migration_subscription_upgrade.sql");
                }
                log.info("迁移完成：订阅积分发放计划表已创建");
            }
        } catch (Exception e) {
            log.warn("subscription_credit_grants 迁移失败: {}", e.getMessage());
        }

        // 2) 订阅行：净支付额 / 覆盖总月数 / 结束时间 / 升级去向 / 自动续费
        addColumnIfNotExists("user_subscriptions", "net_paid_amount",
                "DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '本行覆盖时长内实际净支付总额（元），升级抵扣折算的唯一基数'",
                "status");
        addColumnIfNotExists("user_subscriptions", "total_months",
                "INT NOT NULL DEFAULT 1 COMMENT '覆盖总月数 n（续费累加）'", "net_paid_amount");
        addColumnIfNotExists("user_subscriptions", "ended_at",
                "DATETIME NULL COMMENT '权益结束时间：升级/取消时写入'", "expires_at");
        addColumnIfNotExists("user_subscriptions", "upgraded_to_plan_code",
                "VARCHAR(32) NULL COMMENT '升级去向套餐 code'", "ended_at");
        addColumnIfNotExists("user_subscriptions", "auto_renew",
                "TINYINT(1) NOT NULL DEFAULT 0 COMMENT '自动续费开关（二期使用）'", "upgraded_to_plan_code");
        backfillSubscriptionTotals();

        // 3) 订单：类型 / 原价 / 抵扣额 / 抵扣来源订阅
        addColumnIfNotExists("subscription_orders", "original_amount",
                "DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '新套餐周期原价（抵扣前，元）'", "period_months");
        addColumnIfNotExists("subscription_orders", "discount_amount",
                "DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '本次升级抵扣额（元）'", "original_amount");
        addColumnIfNotExists("subscription_orders", "order_type",
                "VARCHAR(16) NOT NULL DEFAULT 'new' COMMENT 'new(新购) / renew(同档续费) / upgrade(升级替换)'", "amount");
        addColumnIfNotExists("subscription_orders", "source_subscription_id",
                "BIGINT UNSIGNED NULL COMMENT '抵扣来源订阅行'", "order_type");
        backfillOrderAmounts();

        // 4) 计费周期：自动续费资格（数据驱动，禁止运行时解析 code 前缀）
        if (addColumnIfNotExists("subscription_plan_billings", "auto_renew",
                "TINYINT(1) NOT NULL DEFAULT 0 COMMENT '该周期是否具备自动续费资格'", "is_default")) {
            try {
                int rows = jdbcTemplate.update(
                        "UPDATE subscription_plan_billings SET auto_renew = 1 WHERE code LIKE '%-auto'");
                log.info("迁移完成：自动续费资格已回填 {} 条", rows);
            } catch (Exception e) {
                log.warn("自动续费资格回填失败: {}", e.getMessage());
            }
        }

        // 5) 积分批次归属订阅与期数 —— 定位「当月批次」的依据，也是升级回收的定位依据
        addColumnIfNotExists("credit_rewards", "subscription_id",
                "BIGINT UNSIGNED NULL COMMENT '归属订阅（订阅积分批次才有）'", "batch_count");
        addColumnIfNotExists("credit_rewards", "period_index",
                "INT NULL COMMENT '归属期数（第几期）'", "subscription_id");

        // 6) 积分流水类型：存量记录全是消耗，默认值即正确
        addColumnIfNotExists("credit_transactions", "type",
                "VARCHAR(16) NOT NULL DEFAULT 'consume' COMMENT 'consume(消耗) / reclaim(升级回收，负向)'", "cost");

        // 7) 存量订阅标记「已全额发放」—— 必须排在最后，且必须在 grants 表建好之后
        markLegacySubscriptionsFullyGranted();
    }

    /**
     * 回填 {@code total_months} 与 {@code net_paid_amount}（只在缺失时补，不覆盖已有值）。
     *
     * <p>{@code net_paid_amount} 按「同一用户 + 同一套餐」的<b>已支付订单实付</b>汇总：
     * 续费也是同一套餐，累加恰好符合「本行覆盖时长内净支付」的语义。
     * 匹配不到订单时保持 0 —— <b>宁可抵扣为 0，也不按套餐标价猜</b>：
     * 按标价猜等于给用户凭空补贴，且会让「标价剩余价值反复抵扣」的套利路径复活。</p>
     */
    private void backfillSubscriptionTotals() {
        try {
            int months = jdbcTemplate.update(
                    "UPDATE user_subscriptions " +
                            "SET total_months = GREATEST(TIMESTAMPDIFF(MONTH, started_at, expires_at), 1) " +
                            "WHERE total_months IS NULL OR total_months < 1");
            if (months > 0) {
                log.info("迁移完成：订阅覆盖总月数已回填 {} 条", months);
            }
            int paid = jdbcTemplate.update(
                    "UPDATE user_subscriptions s SET net_paid_amount = (" +
                            "SELECT COALESCE(SUM(o.amount), 0) FROM subscription_orders o " +
                            "WHERE o.user_id = s.user_id AND o.plan_code = s.plan_code AND o.status = 'paid') " +
                            "WHERE s.net_paid_amount = 0");
            if (paid > 0) {
                log.info("迁移完成：订阅净支付额已按订单表回填 {} 条", paid);
            }
        } catch (Exception e) {
            log.warn("订阅净支付额回填失败: {}", e.getMessage());
        }
    }

    /** 历史订单的原价列回填：升级前的老订单「原价 = 实付、抵扣 0」。 */
    private void backfillOrderAmounts() {
        try {
            int rows = jdbcTemplate.update(
                    "UPDATE subscription_orders SET original_amount = amount " +
                            "WHERE original_amount = 0 AND amount > 0");
            if (rows > 0) {
                log.info("迁移完成：历史订单原价已回填 {} 条", rows);
            }
        } catch (Exception e) {
            log.warn("历史订单原价回填失败: {}", e.getMessage());
        }
    }

    /**
     * 存量订阅标记「已全额发放」——<b>上线前必须做，否则会重复发积分</b>。
     *
     * <p>旧的发放逻辑是一次发满整周期，库里没有发放计划；新的惰性补发会把「应发而未发」的期数补齐，
     * 于是同一个月的积分会被发第二遍。这里给每个「还没有任何发放计划」的订阅一次性生成整段计划
     * 并全部置 {@code granted}，等于声明「这段历史的积分已经发过了」。</p>
     *
     * <p>幂等：条件就是「该订阅没有任何 grants 行」，新逻辑下单时必预生成计划，所以不会被误标。</p>
     */
    private void markLegacySubscriptionsFullyGranted() {
        try {
            List<Map<String, Object>> legacy = jdbcTemplate.queryForList(
                    "SELECT s.id, s.user_id, s.started_at, s.total_months, s.expires_at, p.monthly_credits " +
                            "FROM user_subscriptions s " +
                            "LEFT JOIN subscription_plans p ON p.code = s.plan_code " +
                            "WHERE NOT EXISTS (SELECT 1 FROM subscription_credit_grants g " +
                            "                  WHERE g.subscription_id = s.id)");
            if (legacy.isEmpty()) {
                return;
            }
            int periods = 0;
            for (Map<String, Object> row : legacy) {
                long subscriptionId = ((Number) row.get("id")).longValue();
                long userId = ((Number) row.get("user_id")).longValue();
                int months = row.get("total_months") == null ? 1 : ((Number) row.get("total_months")).intValue();
                if (months < 1) {
                    months = 1;
                }
                Object startedAt = row.get("started_at");
                BigDecimal amount = row.get("monthly_credits") == null
                        ? BigDecimal.ZERO
                        : BigDecimal.valueOf(((Number) row.get("monthly_credits")).longValue());
                for (int i = 1; i <= months; i++) {
                    periods += jdbcTemplate.update(
                            "INSERT IGNORE INTO subscription_credit_grants " +
                                    "(subscription_id, user_id, period_index, period_start, period_end, " +
                                    " amount, status, granted_at) VALUES " +
                                    "(?, ?, ?, DATE_ADD(?, INTERVAL ? MONTH), DATE_ADD(?, INTERVAL ? MONTH), ?, 'granted', ?)",
                            subscriptionId, userId, i, startedAt, i - 1, startedAt, i, amount, startedAt);
                }
            }
            log.info("迁移完成：存量订阅已标记「已全额发放」（{} 条订阅 / {} 期），不会被按月逻辑重复补发",
                    legacy.size(), periods);
        } catch (Exception e) {
            log.warn("存量订阅标记失败（可能重复发积分，需人工核查）: {}", e.getMessage());
        }
    }

    /**
     * 聊天积分消耗（两阶段扣费）迁移。
     *
     * <p>三张新表（计价 / 预扣单 / 分组倍率）由脚本建；{@code credit_transactions} 的扩列
     * （message_id / hold_id / tokens）单独逐列 ALTER，并用 {@code uk_message} 唯一索引
     * 兜住「同一条消息只结算一次」。</p>
     */
    private void chatBillingTables() {
        try {
            Integer tables = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                            "WHERE TABLE_SCHEMA = DATABASE() " +
                            "AND TABLE_NAME IN ('model_pricing', 'credit_holds', 'credit_group_ratio')",
                    Integer.class
            );
            if (tables != null && tables < 3) {
                try (Connection conn = dataSource.getConnection()) {
                    runScript(conn, "db/migration_chat_billing.sql");
                }
                log.info("迁移完成：聊天扣费表已创建（model_pricing / credit_holds / credit_group_ratio）");
            }
        } catch (Exception e) {
            log.warn("聊天扣费表迁移失败: {}", e.getMessage());
        }

        // 消耗流水扩列：关联消息（幂等键）/ 关联预扣单 / token 明细（明细页展示用）
        addColumnIfNotExists("credit_transactions", "message_id",
                "BIGINT UNSIGNED NULL COMMENT '关联 assistant message id（结算幂等键）'", "type");
        addColumnIfNotExists("credit_transactions", "hold_id",
                "BIGINT UNSIGNED NULL COMMENT '关联预扣单 credit_holds.id（对账用）'", "message_id");
        addColumnIfNotExists("credit_transactions", "input_tokens",
                "INT NULL COMMENT '本次输入 token（真实 usage 或 chars/4 估算）'", "hold_id");
        addColumnIfNotExists("credit_transactions", "output_tokens",
                "INT NULL COMMENT '本次输出 token'", "input_tokens");
        // 估算口径独立成列：标题只写「本轮提问」，不再用 [估算] 前缀承载计费口径
        addColumnIfNotExists("credit_transactions", "estimated",
                "TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1 = tokens 为 chars/4 估算值（上游未返回 usage）'",
                "output_tokens");
        backfillEstimatedColumn();
        addIndexIfNotExists("credit_transactions", "uk_message",
                "UNIQUE KEY uk_message (message_id)");

        // 消息表冗余一份结算结果：历史重载时气泡 footer 要显示「tokens · N 积分」，
        // 冗余存下来就不必在拉历史时反查 credit_transactions（前端也不自算，见前端规范 §8.2）。
        // 结算值由服务端写入，是结算那一刻的快照；后续运营调价不回改历史（历史不该被改价）。
        for (String table : new String[]{"messages", "channel_messages"}) {
            addColumnIfNotExists(table, "credits_cost",
                    "DECIMAL(12,2) NULL COMMENT '本条消息实际消耗积分（两位小数，NULL=未扣费）'", "output_tokens");
            addColumnIfNotExists(table, "credits_estimated",
                    "TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1 = tokens 为 chars/4 估算值（上游未返回 usage）'",
                    "credits_cost");
        }

        widenCreditsColumns();
    }

    /**
     * 工作空间迁移（2026-09-21）。
     *
     * <p>两张新表由脚本建（`db/migration_workspaces.sql`：DDL 为 IF NOT EXISTS、
     * 配额种子为 INSERT IGNORE，可重复执行且不覆盖运营调过的数值）。</p>
     *
     * <p>{@code sessions.workspace_id} 是本需求的权威引用列。历史上那个裸路径列
     * {@code sessions.project_path} 已随 {@link #deprecatedCleanup()} 删除 ——
     * 服务端存本机路径既无法校验、又属隐私泄露（红线 18）。</p>
     */
    private void workspaceTables() {
        try {
            Integer tables = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES " +
                            "WHERE TABLE_SCHEMA = DATABASE() " +
                            "AND TABLE_NAME IN ('workspaces', 'subscription_plan_quotas')",
                    Integer.class
            );
            if (tables != null && tables < 2) {
                try (Connection conn = dataSource.getConnection()) {
                    runScript(conn, "db/migration_workspaces.sql");
                }
                log.info("迁移完成：工作空间表与套餐配额表已创建");
            }
        } catch (Exception e) {
            log.warn("工作空间表迁移失败: {}", e.getMessage());
        }

        addColumnIfNotExists("sessions", "workspace_id",
                "BIGINT UNSIGNED NULL COMMENT '绑定的工作空间 id（权威引用；创建会话时锁定）'",
                "title");
        addIndexIfNotExists("sessions", "idx_workspace",
                "INDEX idx_workspace (workspace_id)");

        // 自动化任务同样要绑空间（2026-09-21）。没有这一列时，任务跑出来的会话恒为「无空间」，
        // 而 AgentEngine 在 workspaceId==null 时会把 8 个本地类工具整体剔除 —— 自动化任务
        // 就只是个「会说不能做」的聊天机器人。⚠️ 类型与 sessions.workspace_id 一致
        // （BIGINT UNSIGNED），否则两列关联比较会走隐式转换、索引失效。
        addColumnIfNotExists("schedules", "workspace_id",
                "BIGINT UNSIGNED NULL COMMENT '绑定的工作空间 id（NULL = 无空间，本地类工具不下发）'",
                "user_id");
        addIndexIfNotExists("schedules", "idx_sched_workspace",
                "INDEX idx_sched_workspace (workspace_id)");
    }

    /**
     * 积分列精度改造：INT → DECIMAL(12,2)（幂等）。
     *
     * <p>根因：积分一开始被当成「粒度为 1 的代币」，所有金额列都是 INT，计价又做
     * {@code max(1, ceil(raw))} —— 一句「你好」的真实成本约 0.001 积分，却按 1 积分收，
     * 小额请求被放大了三个数量级。改成两位小数后，列必须一起加宽，否则
     * Java 侧算出的 0.01 落库会被静默截断成 0（比整数计费更难查：账看着扣了、余额没变）。</p>
     *
     * <p>只跑类型不是 decimal 的列：本方法重跑是空操作，注释也不会被覆盖。</p>
     */
    private void widenCreditsColumns() {
        widenToDecimal("credit_holds", "estimated_cost",
                "DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '预扣额（积分，两位小数）'");
        widenToDecimal("credit_holds", "actual_cost",
                "DECIMAL(12,2) NULL COMMENT '结算后的实际消耗（积分，两位小数）'");
        for (String table : new String[]{"messages", "channel_messages"}) {
            widenToDecimal(table, "credits_cost",
                    "DECIMAL(12,2) NULL COMMENT '本条消息实际消耗积分（两位小数，NULL=未扣费）'");
        }
        // 流水成本列本就是 DECIMAL；这里只是兜住更早的历史库（若曾是 INT 会被静默取整）
        widenToDecimal("credit_transactions", "cost",
                "DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '积分变动量：消耗为正、回收为负'");
    }

    /**
     * 把整数列加宽成 DECIMAL（幂等）：仅当当前 {@code DATA_TYPE} 不是 {@code decimal} 才 ALTER。
     *
     * <p>整数列转 DECIMAL 是安全的向上加宽（不会丢值）；反过来才会截断，所以本方法只做加宽。</p>
     */
    private void widenToDecimal(String table, String column, String definition) {
        try {
            List<String> types = jdbcTemplate.queryForList(
                    "SELECT DATA_TYPE FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    String.class, table, column);
            if (types.isEmpty()) {
                log.debug("{}.{} 不存在，跳过精度加宽", table, column);
                return;
            }
            if ("decimal".equalsIgnoreCase(types.get(0))) {
                log.debug("{}.{} 已是 DECIMAL，跳过", table, column);
                return;
            }
            // 不带 CHARACTER SET：那是字符列的选项，带上会把排序规则退回该字符集的默认值
            jdbcTemplate.execute(String.format(
                    "ALTER TABLE %s MODIFY COLUMN %s %s", table, column, definition));
            log.info("迁移完成：{}.{} 精度已加宽为 DECIMAL(12,2)（原类型 {}）", table, column, types.get(0));
        } catch (Exception e) {
            log.warn("{}.{} 精度加宽失败: {}", table, column, e.getMessage());
        }
    }

    /**
     * 存量消耗流水的口径回填（幂等）。
     *
     * <p>把历史标题里的 {@code [估算] } 前缀剥掉、口径落到 {@code estimated} 列，并且
     * <b>按该轮用户提问重写标题</b> —— 旧实现取的是会话标题（会话标题只是首问），
     * 一个会话问两轮以上时后续每一行的标题都是第一句话，明细页看着像重复行。</p>
     *
     * <p>只处理带前缀的行：那是唯一一批被口径污染过的记录。前缀剥掉后条件不再命中，
     * 重跑是空操作。办公助理的行标题以「办公助理·」开头，且它的 message_id 指向
     * {@code channel_messages}（与 messages 的 id 空间重叠），join 会认错行，故排除。</p>
     */
    private void backfillEstimatedColumn() {
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE credit_transactions t SET t.estimated = 1, t.record_title = COALESCE((" +
                            "  SELECT LEFT(TRIM(REPLACE(REPLACE(m.content, '\\r', ' '), '\\n', ' ')), 32)" +
                            "  FROM messages m" +
                            "  WHERE m.session_id = (SELECT a.session_id FROM messages a WHERE a.id = t.message_id)" +
                            "    AND m.role = 'user' AND m.id < t.message_id" +
                            "  ORDER BY m.id DESC LIMIT 1), " +
                            "  TRIM(REPLACE(t.record_title, '[估算] ', '')))" +
                            " WHERE t.record_title LIKE '[估算] %'" +
                            "   AND t.record_title NOT LIKE '办公助理·%'");
            if (updated > 0) {
                log.info("迁移完成：{} 条消耗流水的标题已剥离 [估算] 前缀并按当轮提问重写", updated);
            } else {
                log.debug("消耗流水标题无需回填（无带 [估算] 前缀的行）");
            }
        } catch (Exception e) {
            log.warn("消耗流水标题回填失败: {}", e.getMessage());
        }
    }

    /** 索引是否存在（information_schema 判定，无副作用） */
    private boolean indexExists(String table, String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, indexName
        );
        return count != null && count > 0;
    }

    /** 补索引（幂等）：已存在直接跳过 */
    private void addIndexIfNotExists(String table, String indexName, String definition) {
        try {
            if (indexExists(table, indexName)) {
                log.debug("{}.{} 已存在，跳过", table, indexName);
                return;
            }
            jdbcTemplate.execute(String.format("ALTER TABLE %s ADD %s", table, definition));
            log.info("迁移完成：{}.{} 索引已添加", table, indexName);
        } catch (Exception e) {
            log.warn("{}.{} 索引迁移失败: {}", table, indexName, e.getMessage());
        }
    }

    /** 列是否存在（information_schema 判定，无副作用） */
    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column
        );
        return count != null && count > 0;
    }

    /** 删列（幂等）：列已不存在直接跳过 */
    private void dropColumnIfExists(String table, String column) {
        try {
            if (!columnExists(table, column)) {
                log.debug("{}.{} 不存在，跳过删除", table, column);
                return;
            }
            jdbcTemplate.execute(String.format("ALTER TABLE %s DROP COLUMN %s", table, column));
            log.info("迁移完成：已删除历史遗留列 {}.{}", table, column);
        } catch (Exception e) {
            log.warn("删除 {}.{} 失败: {}", table, column, e.getMessage());
        }
    }

    /** 表是否存在 */
    private boolean tableExists(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Integer.class, table
        );
        return count != null && count > 0;
    }

    /** 删表（幂等）：表不存在直接跳过 */
    private void dropTableIfExists(String table) {
        try {
            if (!tableExists(table)) {
                log.debug("表 {} 不存在，跳过删除", table);
                return;
            }
            jdbcTemplate.execute("DROP TABLE " + table);
            log.info("迁移完成：已删除废弃表 {}", table);
        } catch (Exception e) {
            log.warn("删除废弃表 {} 失败: {}", table, e.getMessage());
        }
    }

    /**
     * 废弃对象清理（2026-09-22）。
     *
     * <p>把「从未上线」与「已被取代」的对象从库里彻底移除。留着它们不是中性的：
     * 建表脚本里的死表会让人以为功能存在，废弃列会被后来者当作权威字段去读。</p>
     *
     * <p>逐条依据：</p>
     * <ul>
     *   <li>{@code memories} / {@code tool_calls} / {@code plugins} / {@code mcp_servers} ——
     *       只在 {@code schema.sql} 里定义过，**从未在任何环境落库**；配套的
     *       {@code MemoryMapper} / {@code ToolCallMapper} 也无任何注入点（实体与 Mapper 已一并删除）。
     *       工具调用明细由 {@code messages.tool_calls} JSON 列承载，不需要独立表。</li>
     *   <li>{@code sessions.project_path} —— 已被 {@code sessions.workspace_id} 取代。
     *       它在云端架构下既无法校验、又属隐私泄露（红线 18），运行时也无任何消费点。</li>
     *   <li>{@code workspaces.permission_mode} —— 权限档位改为账号级全局生效
     *       （用户 2026-09-21 明确要求），空间级覆盖整体废弃。留着这一列会让人
     *       以为「按空间配权限」是受支持的。</li>
     *   <li>{@code permissions} —— 工具级三态（auto_allow / ask_user / deny）是旧审批模型的
     *       残留：与权限档位职责重叠，且工具的危险性本就写在 {@code Tool#requiredPermission()}
     *       上。审批链已改为「档位 + 工具自身声明 + 客户端沙箱策略」。</li>
     * </ul>
     *
     * <p>幂等：对象不存在时为空操作。调用顺序无要求（都是独立的 DROP）。</p>
     */
    private void deprecatedCleanup() {
        for (String table : new String[]{"memories", "tool_calls", "plugins", "mcp_servers", "permissions"}) {
            dropTableIfExists(table);
        }
        dropColumnIfExists("sessions", "project_path");
        dropColumnIfExists("workspaces", "permission_mode");
    }

    /**
     * 审计中心表（2026-09-22，P4）。
     *
     * <p>沙箱判定在客户端（执行下沉），本表的数据由客户端经 {@code POST /audit/events}
     * 上报落库 —— 服务端只做存储与查询，不做判定。{@code target_digest} 只存摘要：
     * 命令与路径可能含敏感串，原文进服务端既属隐私泄露（红线 18）又无校验价值。</p>
     *
     * <p>幂等：表已存在时跳过（脚本内 DDL 亦为 IF NOT EXISTS，双保险）。</p>
     */
    private void createAuditTable() {
        try {
            if (tableExists("audit_logs")) {
                log.debug("audit_logs 已存在，跳过迁移");
                return;
            }
            try (Connection conn = dataSource.getConnection()) {
                runScript(conn, "db/migration_audit_logs.sql");
            }
            log.info("迁移完成：审计中心表 audit_logs 已创建");
        } catch (Exception e) {
            log.warn("audit_logs 迁移失败: {}", e.getMessage());
        }
    }

    /**
     * P5：audit_logs 增加隔离层级列（存量库幂等补列）。
     *
     * <p>取值 {@code os-fence} / {@code policy-only}。存量为默认 {@code policy-only}：
     * P4 时代的记录确实只经策略层执行，如实回填，不假称隔离过。</p>
     */
    private void addAuditIsolationColumn() {
        try {
            if (tableExists("audit_logs")) {
                addColumnIfNotExists("audit_logs", "isolation",
                        "VARCHAR(16) NOT NULL DEFAULT 'policy-only' COMMENT 'P5 隔离层级：os-fence / policy-only'",
                        "reason");
            }
        } catch (Exception e) {
            log.warn("audit_logs.isolation 补列失败: {}", e.getMessage());
        }
    }

    /**
     * 权限档位：四档 → 两档（2026-09-22，对齐 WorkBuddy）。
     *
     * <p>取值范围由 {@code manual / smart / fully / custom} 收敛为 {@code default / full}。
     * 存量迁移方向：{@code fully} → {@code full}（用户当初选的就是「不再逐次审批」），
     * 其余一律 → {@code default}。</p>
     *
     * <p>🔴 <b>为什么「其余一律 default」而不是按语义一一对应</b>：旧 {@code smart} 是
     * 「重要操作由 AI 自动批准」，新模型里没有对应物 —— 它既不是完全放开、也不是逐次确认。
     * 迁到 {@code default} 是多问一次（安全侧）；迁到 {@code full} 等于替用户把审批全关了。
     * 同理 {@code custom} 的自定义规则在新模型里由客户端沙箱策略承载，服务端只知道两档。</p>
     *
     * <p>幂等：取值已合法时 UPDATE 命中 0 行；列定义的 ALTER 只在默认值/可空性不符时执行，
     * 不会每次启动都重建表（{@code MODIFY COLUMN} 对 VARCHAR 是重建操作）。</p>
     */
    private void permissionModeTwoTier() {
        try {
            int upgraded = jdbcTemplate.update(
                    "UPDATE users SET permission_mode = 'full' WHERE permission_mode = 'fully'");
            if (upgraded > 0) {
                log.info("迁移完成：{} 个用户的权限档位 fully → full", upgraded);
            }
            int downgraded = jdbcTemplate.update(
                    "UPDATE users SET permission_mode = 'default' " +
                            "WHERE permission_mode IS NULL OR permission_mode NOT IN ('default', 'full')");
            if (downgraded > 0) {
                log.info("迁移完成：{} 个用户的权限档位已收敛为 default", downgraded);
            }
        } catch (Exception e) {
            log.warn("权限档位取值迁移失败: {}", e.getMessage());
        }

        // 列定义对齐 schema.sql：NOT NULL DEFAULT 'default'
        try {
            List<Map<String, Object>> cols = jdbcTemplate.queryForList(
                    "SELECT COLUMN_DEFAULT, IS_NULLABLE FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' " +
                            "AND COLUMN_NAME = 'permission_mode'");
            if (cols.isEmpty()) {
                log.debug("users.permission_mode 不存在，跳过列定义对齐");
                return;
            }
            Object def = cols.get(0).get("COLUMN_DEFAULT");
            Object nullable = cols.get(0).get("IS_NULLABLE");
            boolean upToDate = def != null && "default".equalsIgnoreCase(String.valueOf(def))
                    && !"YES".equalsIgnoreCase(String.valueOf(nullable));
            if (upToDate) {
                log.debug("users.permission_mode 列定义已符合预期，跳过");
                return;
            }
            jdbcTemplate.execute(
                    "ALTER TABLE users MODIFY COLUMN permission_mode " +
                            "VARCHAR(16) NOT NULL DEFAULT 'default' COMMENT 'default(默认权限) / full(完全访问权限)'");
            log.info("迁移完成：users.permission_mode 列定义已对齐（NOT NULL DEFAULT 'default'）");
        } catch (Exception e) {
            log.warn("users.permission_mode 列定义对齐失败: {}", e.getMessage());
        }
    }

    /**
     * 补列（幂等）。
     *
     * @return <b>本次真的执行了 ALTER 才返回 true</b>；列已存在或迁移失败均为 false，
     *         调用方可据此决定「是否需要回填数据」。
     */
    private boolean addColumnIfNotExists(String table, String column, String definition, String afterColumn) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class, table, column
            );
            if (count != null && count > 0) {
                log.debug("{}.{} 已存在，跳过", table, column);
                return false;
            }
            jdbcTemplate.execute(String.format(
                    "ALTER TABLE %s ADD COLUMN %s %s AFTER %s", table, column, definition, afterColumn
            ));
            log.info("迁移完成：{}.{} 字段已添加", table, column);
            return true;
        } catch (Exception e) {
            log.warn("{}.{} 迁移失败: {}", table, column, e.getMessage());
            return false;
        }
    }

    /**
     * 执行 classpath 下的 SQL 脚本。
     *
     * <p><b>必须用 {@link EncodedResource} 显式指定 UTF-8</b>：单参重载
     * {@code executeSqlScript(conn, resource)} 会退回
     * {@link java.nio.charset.Charset#defaultCharset()} —— zh_CN 的 Windows + JDK 17 上是 GBK，
     * 脚本里的中文备注与种子数据会被按 GBK 误读后写进库，落成不可逆的乱码
     * （U+FFFD 已经吃掉原始字节，事后只能重写，救不回来）。</p>
     */
    private void runScript(Connection conn, String classpathLocation) {
        ScriptUtils.executeSqlScript(
                conn, new EncodedResource(new ClassPathResource(classpathLocation), StandardCharsets.UTF_8));
    }

    /**
     * 全库表排序规则兜底：与库默认（utf8mb4_unicode_ci）不一致的表统一过来。
     *
     * <p>为什么需要这一层：{@code CREATE TABLE ... DEFAULT CHARSET=utf8mb4} <b>只写字符集</b>时，
     * 表排序规则取的是「该字符集的默认值」（MySQL 8 = utf8mb4_0900_ai_ci），而<b>不是</b>库默认的
     * utf8mb4_unicode_ci —— 同一张库里两种 ci 规则并存，跨表 JOIN 字符串列随时会撞上
     * "Illegal mix of collations"。脚本已经逐条显式声明 COLLATE，这里再兜一层，
     * 免得将来谁漏写又把库搞成两种规则。</p>
     *
     * <p>幂等：全部一致时只查一次 information_schema 就返回，不做任何 DDL。</p>
     */
    private void ensureUtf8mb4Collation() {
        try {
            List<String> mismatched = jdbcTemplate.queryForList(
                    "SELECT TABLE_NAME FROM information_schema.TABLES " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' " +
                            "AND TABLE_COLLATION <> 'utf8mb4_unicode_ci'",
                    String.class);
            if (mismatched.isEmpty()) {
                return;
            }
            for (String table : mismatched) {
                jdbcTemplate.execute(String.format(
                        "ALTER TABLE `%s` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci", table));
                log.info("迁移完成：{} 排序规则已统一为 utf8mb4_unicode_ci", table);
            }
        } catch (Exception e) {
            log.warn("排序规则统一失败: {}", e.getMessage());
        }
    }
}

package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订阅积分发放计划 subscription_credit_grants
 *
 * <p>一次订阅 = 覆盖总月数份<b>月度权益包</b>，每份一行：
 * <ol>
 *   <li>{@code period_index} 是第几期（1-based），{@code period_start / period_end} 是该期区间；</li>
 *   <li>下单时<b>预生成整段计划</b>（年付 = 12 行，第 1 期立即发，其余 {@code pending}）；</li>
 *   <li>升级时旧订阅剩余 {@code pending} 整体置 {@code cancelled} —— 未生效月的积分从未发放，
 *       所以折算退款时不需要回收未来月的积分，这正是「按月发放」比「一次发满」简单的地方。</li>
 * </ol>
 *
 * <p><b>幂等基石</b>是表上的唯一键 {@code uk_sub_period (subscription_id, period_index)}：
 * 惰性补发会在「读积分」「登录」「下单」等多个入口触发，必须靠唯一键 + 条件更新兜住重复入账。</p>
 */
@Data
@TableName("subscription_credit_grants")
public class SubscriptionCreditGrant {

    /** 未发放（到点后由惰性补发处理） */
    public static final String STATUS_PENDING = "pending";

    /** 已发放 */
    public static final String STATUS_GRANTED = "granted";

    /** 已取消：订阅被升级替换，该期不再发放 */
    public static final String STATUS_CANCELLED = "cancelled";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属订阅，对应 user_subscriptions.id */
    @TableField("subscription_id")
    private Long subscriptionId;

    /** 关联 users.id（冗余，便于按用户批量补发） */
    @TableField("user_id")
    private Long userId;

    /** 第几期（1-based） */
    @TableField("period_index")
    private Integer periodIndex;

    /** 该期开始时间；到点即可发放 */
    @TableField("period_start")
    private LocalDateTime periodStart;

    /** 该期结束时间 */
    @TableField("period_end")
    private LocalDateTime periodEnd;

    /** 该期发放积分（下单时的套餐额度快照，改价不影响已生成的计划） */
    private BigDecimal amount;

    /** pending / granted / cancelled */
    private String status;

    /** 发放后关联的 credit_rewards.id */
    @TableField("reward_id")
    private Long rewardId;

    /** 实际发放时间 */
    @TableField("granted_at")
    private LocalDateTime grantedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

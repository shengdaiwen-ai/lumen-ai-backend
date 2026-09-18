package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 奖励积分批次 credit_rewards
 *
 * <p>按奖励来源聚合一行：remaining / total 是剩余与累计值，
 * batch_count 记录批次数（前端「共 N 笔」），expires_at 为到期时间。</p>
 */
@Data
@TableName("credit_rewards")
public class CreditReward {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    /** 奖励名称，如 老用户福利 / 每日签到 / 每月登录赠送 */
    private String title;

    /** general(通用积分，当前唯一在用)；预留扩展类型（如平台奖励积分） */
    @TableField("credit_type")
    private String creditType;

    /** 剩余可用 */
    private BigDecimal remaining;

    /** 累计获得 */
    private BigDecimal total;

    /** 到期时间，NULL 表示无到期 */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** 批次数量，>1 时前端展示「共 N 笔」 */
    @TableField("batch_count")
    private Integer batchCount;

    /**
     * 归属订阅（只有订阅积分批次才有），对应 user_subscriptions.id。
     *
     * <p>升级折算要定位「当月批次」并按其 {@code remaining / total} 算回收比例，
     * 靠本列 + {@code period_index} 精确定位；<b>不按时间区间撮合流水</b>
     * —— 用户可能同时在消耗加量包或活动积分，按区间统计会失真。</p>
     */
    @TableField("subscription_id")
    private Long subscriptionId;

    /** 归属期数（第几期，1-based），与 subscription_credit_grants.period_index 对应 */
    @TableField("period_index")
    private Integer periodIndex;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

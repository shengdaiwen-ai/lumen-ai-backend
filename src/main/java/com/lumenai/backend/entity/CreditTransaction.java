package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 积分消耗流水 credit_transactions
 */
@Data
@TableName("credit_transactions")
public class CreditTransaction {

    /** 消耗（cost 为正） */
    public static final String TYPE_CONSUME = "consume";

    /** 升级回收（cost 为负）：旧套餐当月未消耗部分的积分按折算比例收回 */
    public static final String TYPE_RECLAIM = "reclaim";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    /** general(通用积分，当前唯一在用)；预留扩展类型（如平台奖励积分） */
    @TableField("credit_type")
    private String creditType;

    /** 使用记录标题 */
    @TableField("record_title")
    private String recordTitle;

    /** 模型名称 */
    @TableField("model_name")
    private String modelName;

    /** 产品端 */
    private String product;

    /**
     * 积分变动量：<b>消耗为正、回收为负</b>（符号与 {@link #type} 一致，前端可直接展示）。
     * 改造前全是消耗，存量数据与旧口径完全兼容。
     */
    private BigDecimal cost;

    /** consume(消耗) / reclaim(升级回收，负向) */
    private String type;

    /**
     * 关联的 assistant message id。
     *
     * <p>结算幂等的第二道防线（第一道是预扣单状态抢占）：唯一索引 {@code uk_message} 保证
     * 同一条消息只会有一条消耗流水。历史演示数据与统计类流水不带本列，NULL 不参与唯一约束。</p>
     */
    @TableField("message_id")
    private Long messageId;

    /** 关联预扣单 id（对账用） */
    @TableField("hold_id")
    private Long holdId;

    /** 本次输入 token（真实 usage 或 chars/4 估算） */
    @TableField("input_tokens")
    private Integer inputTokens;

    /** 本次输出 token */
    @TableField("output_tokens")
    private Integer outputTokens;

    /**
     * 本条的 tokens 是否为 chars/4 估算值（上游未返回 usage）。
     *
     * <p>独立成列而不是写进 {@link #recordTitle} 的 [估算] 前缀：标题是给人看「这是哪一轮提问」的，
     * 计费口径属于结构化字段，不该混进业务文本（否则明细页每行都带前缀，用户以为数据坏了）。</p>
     */
    @TableField("estimated")
    private Boolean estimated;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

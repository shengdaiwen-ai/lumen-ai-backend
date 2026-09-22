package com.lumenai.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 一次扣费的结算结果（done 事件 credits 字段 / 服务端内部汇总）
 *
 * <p>恒等式 {@code cost + refunded = preConsumed} 在服务端被断言 —— 预扣了多少，
 * 要么变成消耗，要么退回，中间不能有第三种去向（对账的根）。</p>
 */
@Data
@Builder
public class CreditsInfoVO {

    /** 本次实际消耗积分（两位小数，列 DECIMAL(12,2)） */
    private BigDecimal cost;

    /** 预扣额（积分）：请求前先按估算扣掉 */
    private BigDecimal preConsumed;

    /** 退回的差额（积分）：预扣额 − 实际消耗 */
    private BigDecimal refunded;

    /** 扣费后余额 */
    private BigDecimal balanceAfter;

    /** 本次 token（真实或估算） */
    private Integer inputTokens;

    private Integer outputTokens;

    /** true = token 为 chars/4 估算值（气泡与用量明细标「约」） */
    private boolean estimated;
}

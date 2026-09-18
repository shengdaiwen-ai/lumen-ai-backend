package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户余额流水 balance_transactions
 *
 * <p>规范 §10.3：金额变动必须留痕 —— 只改 {@code users.balance} 不留流水无法对账，
 * 因此每次扣减都同步写一条本表记录（{@code change_amount} 消费为负）。</p>
 */
@Data
@TableName("balance_transactions")
public class BalanceTransaction {

    /** 充值 */
    public static final String TYPE_RECHARGE = "recharge";

    /** 消费 */
    public static final String TYPE_CONSUME = "consume";

    /** 退款 */
    public static final String TYPE_REFUND = "refund";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    /** 变动金额（元）：充值/退款为正，消费为负 */
    @TableField("change_amount")
    private BigDecimal changeAmount;

    /** 变动后余额（元） */
    @TableField("balance_after")
    private BigDecimal balanceAfter;

    /** recharge / consume / refund */
    private String type;

    /** 关联业务类型，如 subscription_order */
    @TableField("ref_type")
    private String refType;

    /** 关联业务主键 */
    @TableField("ref_id")
    private Long refId;

    /** 备注，如 购买旗舰版·连续包年 */
    private String remark;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

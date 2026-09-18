package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户积分账户 credit_accounts
 *
 * <p>按积分类型分账：general(通用积分，当前唯一在用)；后续会扩展平台奖励积分等新类型。
 * 同一用户同一类型只有一行，由唯一键 uk_user_type 保证。</p>
 */
@Data
@TableName("credit_accounts")
public class CreditAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    /** general / work */
    @TableField("credit_type")
    private String creditType;

    /** 产品端，如 Lumen AI */
    private String product;

    private BigDecimal balance;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 积分计价分组倍率 credit_group_ratio
 *
 * <p>按档位 / 通道分级配倍率：最终消耗 = 模型单价 × 分组倍率。作用域三层，
 * 查找顺序 <b>plan:&lt;当前套餐&gt; → channel:&lt;通道&gt; → default</b>，先命中即用
 * （套餐倍率优先于通道倍率，运营语义直白）。全 1.0 时等价于没有倍率。</p>
 */
@Data
@TableName("credit_group_ratio")
public class CreditGroupRatio {

    /** 兜底作用域 */
    public static final String SCOPE_DEFAULT = "default";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** plan:<plan_code> / channel:<channel> / default */
    private String scope;

    /** 倍率 0~10 */
    private BigDecimal ratio;

    private String remark;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型计价 model_pricing
 *
 * <p>单价单位是<b>积分/千 token</b>，与前端明细、上游 token 数直接对应。
 * 价格属于运营数据，**必须查库**（红线 14），改价不需要改代码。</p>
 */
@Data
@TableName("model_pricing")
public class ModelPricing {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模型标识，与 done 事件 / messages.model 一致 */
    @TableField("model_code")
    private String modelCode;

    /** 展示名，如 Pro / Flash */
    @TableField("display_name")
    private String displayName;

    /** 输入单价：积分/千token */
    @TableField("input_price")
    private BigDecimal inputPrice;

    /** 输出单价：积分/千token */
    @TableField("output_price")
    private BigDecimal outputPrice;

    /** 缓存命中输入的折率 0~1（1=不折） */
    @TableField("cache_ratio")
    private BigDecimal cacheRatio;

    /** 1 = 免费模型，消耗 0 积分 */
    @TableField("is_free")
    private Integer isFree;

    /** 1 = 未登记模型的兜底单价（兜底价也走表，不在代码里写价格） */
    @TableField("is_fallback")
    private Integer isFallback;

    /** 预扣估算用的单次输出上限（token） */
    @TableField("max_output")
    private Integer maxOutput;

    /** active / disabled */
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

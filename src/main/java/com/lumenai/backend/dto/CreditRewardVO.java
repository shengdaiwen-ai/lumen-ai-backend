package com.lumenai.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 奖励积分条目出参
 */
@Data
@Builder
public class CreditRewardVO {

    private Long id;

    /** 奖励名称 */
    private String title;

    /** general(通用积分，当前唯一在用)；预留扩展类型（如平台奖励积分），显示名由前端映射 */
    private String type;

    /** 剩余可用 */
    private BigDecimal remaining;

    /** 累计获得 */
    private BigDecimal total;

    /** 到期时间 yyyy-MM-dd HH:mm，null 表示无到期 */
    private String expiresAt;

    /** 批次数量，>1 时前端展示「共 N 笔」 */
    private Integer batchCount;
}

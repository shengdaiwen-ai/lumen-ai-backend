package com.lumenai.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 积分总览出参
 */
@Data
@Builder
public class CreditOverviewVO {

    /** 总可用积分（各类型账户余额之和） */
    private BigDecimal totalAvailable;

    /** 各积分类型账户明细 */
    private List<AccountItem> accounts;

    @Data
    @Builder
    public static class AccountItem {

        /** general(通用积分，当前唯一在用)；预留扩展类型（如平台奖励积分），显示名由前端映射 */
        private String type;

        /** 产品端，如 Lumen AI */
        private String product;

        /** 可用余额 */
        private BigDecimal balance;
    }
}

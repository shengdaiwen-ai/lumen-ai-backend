package com.lumenai.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 积分用量明细分页出参
 */
@Data
@Builder
public class CreditUsageVO {

    private long total;

    private long pageNum;

    private long pageSize;

    private long totalPages;

    /** 统计区间起始 yyyy-MM-dd，null 表示不限时间 */
    private String startDate;

    /** 统计区间截止 yyyy-MM-dd，null 表示不限时间 */
    private String endDate;

    private List<UsageRecord> records;

    @Data
    @Builder
    public static class UsageRecord {

        private Long id;

        /** 发生时间 yyyy-MM-dd HH:mm */
        private String time;

        /** 使用记录标题：本轮用户提问（不是会话标题） */
        private String record;

        /** 模型名称 */
        private String model;

        /** 产品端 */
        private String product;

        /** 消耗积分 */
        private BigDecimal cost;

        /** 本次输入 token（对话消耗才有） */
        private Integer inputTokens;

        /** 本次输出 token（对话消耗才有） */
        private Integer outputTokens;

        /** true = 上面的 tokens 是 chars/4 估算值（前端在 tokens 旁标「约」） */
        private Boolean estimated;
    }
}

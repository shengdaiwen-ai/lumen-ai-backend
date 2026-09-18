package com.lumenai.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订阅套餐 VO（GET /subscription/plans 出参）
 */
@Data
@Builder
public class SubscriptionPlanVO {

    /** 套餐标识，如 standard / premium / flagship */
    private String id;

    /** 套餐名，如 标准版 */
    private String name;

    /** 一句话描述，如 4,000 积分，覆盖日常所需 */
    private String description;

    /**
     * 是否默认选中。
     * <p>规则：用户「买了且没过期」→ 选中其<b>已购套餐的下一档</b>（弹框是升级入口，落点是下一步要买的那档；
     * 已是最高档则选中自身，只能续费）；否则选中 is_default 标记的套餐（标准版）。</p>
     */
    private boolean selected;

    /** 是否用户已购且仍在有效期内（用户当前持有的那一档，与 selected 无关；用于展示「当前套餐」标识） */
    private boolean owned;

    /**
     * 是否禁止购买（前端置灰、不可点击）。
     * <p>低于已购档的一律为 true；等于已购档时仅当存在更高档才为 true（最高档放开自身，允许续费）；
     * 从未购买时全部为 false。前端直接消费，不要自己按档位算。</p>
     */
    private boolean locked;

    /** 已购套餐的有效期至（yyyy-MM-dd），未购买为 null */
    private String ownedUntil;

    /** 权益条目 */
    private List<Feature> features;

    /** 计费周期选项 */
    private List<BillingOption> billingOptions;

    @Data
    @Builder
    public static class Feature {
        /** 图标标识，前端映射为具体图标：sparkles/calendar/globe/infinity/list */
        private String icon;
        /** 权益文案 */
        private String text;
    }

    @Data
    @Builder
    public static class BillingOption {
        /** 选项标识，如 monthly-auto / monthly / yearly-auto / yearly */
        private String id;
        /** 展示名，如 连续包月 / 1个月 / 连续包年 / 1年 */
        private String label;
        /** 折扣标签，如 7折；无折扣为 null */
        private String discountLabel;
        /** 价格（元） */
        private BigDecimal price;
        /** 价格单位，/月 或 /年（仅展示） */
        private String unit;
        /** 计费周期时长（月）：1=月付，12=年付。前端据此算「有效期至」预览，不要靠 unit 文案猜 */
        private Integer periodMonths;
        /** 是否该套餐的默认计费选项 */
        private boolean defaultOption;
    }
}

package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天积分预扣单 credit_holds
 *
 * <p>one-api 式两阶段扣费的中间态载体：请求前按估算预扣（{@code held}），
 * 完成后按真实 usage 结算（{@code settled}，多退少补），失败/僵尸单退款
 * （{@code refunded} / {@code expired}）。<b>状态是幂等的唯一凭据</b>：
 * 只允许 {@code held → settled/refunded/expired} 的条件更新抢占，
 * 抢不到的调用方一律跳过，绝不重复扣款或重复退款。</p>
 */
@Data
@TableName("credit_holds")
public class CreditHold {

    /** 已预扣，等待结算 */
    public static final String STATUS_HELD = "held";

    /** 已结算（实际消耗已落账） */
    public static final String STATUS_SETTLED = "settled";

    /** 已全额退款（请求失败、零产出） */
    public static final String STATUS_REFUNDED = "refunded";

    /** 僵尸单被惰性清扫退款（进程崩溃/结算异常后遗留） */
    public static final String STATUS_EXPIRED = "expired";

    /** Web 聊天通道 */
    public static final String CHANNEL_WEB = "web";

    /** 办公助理（微信 iLink Bot）通道 */
    public static final String CHANNEL_ILINK = "ilink";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等键：Web = 每次请求 UUID；办公助理 = 消息 id 派生 */
    @TableField("request_id")
    private String requestId;

    @TableField("user_id")
    private Long userId;

    /** web / ilink */
    private String channel;

    /** Web 会话 id；办公助理通道无会话概念则为 NULL */
    @TableField("conversation_id")
    private Long conversationId;

    @TableField("model_code")
    private String modelCode;

    /** 预扣额（积分） */
    @TableField("estimated_cost")
    private Integer estimatedCost;

    /** 结算后的实际消耗（积分），未结算为 NULL */
    @TableField("actual_cost")
    private Integer actualCost;

    /** held / settled / refunded / expired */
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("settled_at")
    private LocalDateTime settledAt;
}

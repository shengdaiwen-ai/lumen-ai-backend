package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 办公助理通道消息（跨微信/飞书的双向消息）
 */
@Data
@TableName("channel_messages")
public class ChannelMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long accountId;

    /** weixin / feishu */
    private String channel;

    /** 对方 ID（个人 open_id 或群 chat_id） */
    private String peerId;

    private String peerName;

    /** inbound（手机→服务）/ outbound（服务→手机） */
    private String direction;

    /** text / image / file / typing */
    private String messageType;

    /** 消息内容或 JSON 元数据 */
    private String content;

    private String mediaUrl;

    /** iLink 侧消息 ID（去重用） */
    private String ilinkMsgId;

    /** iLink context_token（回复消息用） */
    private String contextToken;

    private Boolean isRead;

    /** 是否已由 Agent 处理并回复 */
    private Boolean agentExecuted;

    /** AI 回复消耗的输入 token（仅 outbound 的 AI 消息有值） */
    @TableField("input_tokens")
    private Integer inputTokens;

    /** AI 回复消耗的输出 token（仅 outbound 的 AI 消息有值） */
    @TableField("output_tokens")
    private Integer outputTokens;

    /**
     * 本条消息实际消耗积分（服务端两阶段结算结果；NULL = 未扣费）。
     * 两位小数（列 DECIMAL(12,2)），与 Web 聊天的 messages.credits_cost 同口径。
     */
    @TableField("credits_cost")
    private BigDecimal creditsCost;

    /** tokens 是否为 chars/4 估算（前端展示「约」） */
    @TableField("credits_estimated")
    private Boolean creditsEstimated;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}

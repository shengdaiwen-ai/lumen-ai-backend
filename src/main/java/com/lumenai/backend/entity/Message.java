package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
@TableName("messages")
@Slf4j
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private Long sessionId;

    @TableField("user_id")
    private Long userId;

    private String role;

    private String content;

    @TableField("tool_calls")
    private String toolCalls;

    @TableField("tool_result")
    private String toolResult;

    /** DB 存 JSON 字符串，对外通过 getAttachmentsList() 返回解析后的 List */
    @JsonIgnore
    private String attachments;

    private String model;

    @TableField("input_tokens")
    private Integer inputTokens;

    @TableField("output_tokens")
    private Integer outputTokens;

    @TableField("latency_ms")
    private Integer latencyMs;

    /**
     * 本条消息实际消耗积分（服务端两阶段结算结果；NULL = 未扣费，如免费模型）。
     * 两位小数（列 DECIMAL(12,2)）—— 积分不是粒度为 1 的代币。
     */
    @TableField("credits_cost")
    private BigDecimal creditsCost;

    /** tokens 是否为 chars/4 估算（上游未返回 usage 时置位，前端展示「约」） */
    @TableField("credits_estimated")
    private Boolean creditsEstimated;

    private Boolean cancelled;

    @TableField("created_at")
    private LocalDateTime createdAt;

    /** Jackson 序列化时调用，把 DB 里的 JSON 串解析成 List 返回给前端 */
    @JsonProperty("attachments")
    public List<Map<String, Object>> getAttachmentsList() {
        if (attachments == null || attachments.isBlank()) return Collections.emptyList();
        try {
            ObjectMapper om = new ObjectMapper();
            return om.readValue(attachments, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("解析 attachments JSON 失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}

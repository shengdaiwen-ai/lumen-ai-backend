package com.lumenai.backend.dto;

import lombok.Data;

/**
 * 客户端回传的工具执行结果（上行 {@code POST /chat/tool-result/{callId}} 的请求体）。
 *
 * <p>体积上限 256KB（需求 §10）：超限时客户端截断内容并置 {@code meta.truncated}，
 * 而不是把整份文件塞进 SSE —— 单次结果过大会把上下文与计费一起打爆。</p>
 */
@Data
public class ToolResultRequest {

    /** 执行是否成功 */
    private Boolean ok;

    /** 成功时的输出文本（可能被截断） */
    private String content;

    /** 失败原因（结构化消息，会原样喂回模型） */
    private String error;

    /** 客户端上报的执行元信息 */
    private Meta meta;

    @Data
    public static class Meta {
        /** 客户端侧耗时（毫秒） */
        private Long durationMs;
        /** 输出是否被截断（超 256KB） */
        private Boolean truncated;
        /** 客户端返回的结构化错误码（如 PATH_OUTSIDE_WORKSPACE），便于服务端日志分类 */
        private String errorCode;
    }

    public boolean isOk() {
        return Boolean.TRUE.equals(ok);
    }

    public boolean isTruncated() {
        return meta != null && Boolean.TRUE.equals(meta.getTruncated());
    }
}

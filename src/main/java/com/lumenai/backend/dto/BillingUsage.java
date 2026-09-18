package com.lumenai.backend.dto;

import lombok.Data;

/**
 * 一次请求的用量快照（AgentEngine → ChatController → 扣费服务 的数据载体）
 *
 * <p>为什么需要它：SSE 的 done 事件由 AgentEngine 在<b>自己的线程</b>里发出，而客户端断连
 * （用户点「停止」）时下游的 {@code doOnNext/map} 根本不会执行 —— 此时唯一能拿到真实
 * usage 的时机就是 AgentEngine 的 finally 块。把快照写进这个对象，
 * 正常路径（done 事件里结算）与断连路径（{@code doFinally} 里结算）就都能读到同一份数据，
 * <b>取消也照实结算，不会白跑不扣费</b>。</p>
 *
 * <p>字段全部 volatile：写入在 agent 线程，读取在 WebFlux 回调线程。</p>
 */
@Data
public class BillingUsage {

    /** 实际使用的模型（AgentEngine 兜底为配置模型） */
    private volatile String model;

    /** 整任务累计输入 token（含全部工具轮次） */
    private volatile int inputTokens;

    /** 整任务累计输出 token */
    private volatile int outputTokens;

    /** 缓存命中的输入 token（DeepSeek prompt_cache_hit_tokens），缺失为 0 */
    private volatile int cacheHitTokens;

    /** 缓存未命中的输入 token，缺失为 0 */
    private volatile int cacheMissTokens;

    /** true = usage 缺失，token 数是 chars/4 估算值（落库到流水的 estimated 列，明细页标「约」） */
    private volatile boolean estimated;

    /** 已产出的正文字符数，用于判定「是否零产出」（零产出全额退款） */
    private volatile int contentLength;

    /** 本请求是否已在 done 事件处结算过（避免 doFinally 重复结算） */
    private volatile boolean settled;

    /** 总 token 数（含缓存拆分缺失时的兜底） */
    public int totalTokens() {
        return inputTokens + outputTokens;
    }

    /** 缓存未命中输入：上游没给拆分字段时，全部输入都按未命中计（保守，不占用户便宜也不让平台倒贴） */
    public int missTokens() {
        if (cacheHitTokens <= 0 && cacheMissTokens <= 0) {
            return inputTokens;
        }
        return cacheMissTokens;
    }
}

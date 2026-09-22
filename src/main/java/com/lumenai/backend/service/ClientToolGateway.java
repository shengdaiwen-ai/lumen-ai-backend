package com.lumenai.backend.service;

import com.lumenai.backend.common.exception.ErrorCode;
import com.lumenai.backend.dto.ToolCallEvent;
import com.lumenai.backend.dto.ToolResultRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 工具执行通道（核心）：把「服务端要执行某个工具」变成「请客户端执行并回传结果」。
 *
 * <p>复用既有的审批范式（{@code AgentEngine#pendingPermissions} + {@code CountDownLatch}），
 * 因此<b>不需要 WebSocket，也不引入新协议栈</b>：
 * 下行走既有 SSE 流发 {@code tool_call} 事件，上行走新增的 REST 端点回传结果。</p>
 *
 * <p>🔴 <b>超时与断线都必须解锁</b>：等待线程是 Agent 循环所在的线程，
 * 一旦某次工具调用永不返回，整条会话的 SSE 流就悬挂住、线程也泄漏掉。
 * 所以等待一律带 deadline，且流终止时 {@link #releaseAllForUser} 兜底唤醒。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientToolGateway {

    /** 默认等待上限：普通文件操作足够宽裕 */
    private static final long DEFAULT_TIMEOUT_MS = 120_000L;

    /** 命令类工具的上限（构建 / 安装动辄几分钟） */
    private static final long LONG_TIMEOUT_MS = 600_000L;

    private final ObjectMapper objectMapper;

    /** 在途的工具调用：callId → pending */
    private final Map<String, PendingToolCall> pending = new ConcurrentHashMap<>();

    /**
     * 一次工具调用等待中的上下文。
     *
     * <p>{@code result} 用 volatile：写入发生在上行 HTTP 线程，读取发生在 Agent 循环线程，
     * {@code latch} 只保证可见性顺序，字段本身仍需 volatile 兜底。</p>
     */
    private static final class PendingToolCall {
        final String callId;
        final Long userId;
        final long deadlineAt;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile ToolResultRequest result;
        /** 被主动取消（用户停止 / 流断开）时为 true，用于区分「超时」与「断线」 */
        volatile boolean cancelled;

        PendingToolCall(String callId, Long userId, long deadlineAt) {
            this.callId = callId;
            this.userId = userId;
            this.deadlineAt = deadlineAt;
        }
    }

    /**
     * 执行上下文：一次 Agent 运行期间的不变量。
     *
     * @param userId      归属用户（上行时必须校验，防越权解锁他人任务）
     * @param sessionId   会话 id（日志用）
     * @param workspaceId 会话绑定的空间；为 null 表示无空间会话 → 本地类工具本不该被下发
     * @param permissionMode 服务端判定的生效权限档位（default / full）；仅随事件下行作展示，客户端侧另有本地沙箱策略
     * @param sink        SSE 通道（下行 tool_call / tool_cancel）
     */
    public record ToolContext(
            Long userId,
            Long sessionId,
            Long workspaceId,
            String permissionMode,
            Sinks.Many<ServerSentEvent<String>> sink
    ) {
    }

    /**
     * 下发一次工具调用并等待客户端回传。
     *
     * @param toolName 工具名（见 {@link ToolRouter#CLIENT_TOOLS}）
     * @param argsJson 模型给的原始参数（JSON 字符串）
     * @return 给模型看的工具输出；失败时返回 {@code ERROR [CODE]: message} 形式的结构化错误
     */
    public String dispatch(String toolName, String argsJson, ToolContext ctx) {
        if (ctx == null || ctx.workspaceId() == null) {
            // 无空间会话里模型幻觉调用本地工具：结构化拒绝，而不是让它去动服务端磁盘
            return error(ErrorCode.WORKSPACE_REQUIRED,
                    "当前会话未绑定工作空间，无法使用文件与命令类工具。请先在输入框左下角选择一个工作空间，再新建会话。");
        }
        if (ctx.sink() == null) {
            return error(ErrorCode.TOOL_DISCONNECTED, "SSE 通道不可用，工具调用未下发");
        }

        long timeoutMs = ToolRouter.isLongRunningTool(toolName) ? LONG_TIMEOUT_MS : DEFAULT_TIMEOUT_MS;
        String callId = UUID.randomUUID().toString();
        PendingToolCall entry = new PendingToolCall(callId, ctx.userId(), System.currentTimeMillis() + timeoutMs);
        pending.put(callId, entry);

        try {
            emit(ctx.sink(), "tool_call", new ToolCallEvent(
                    callId, toolName, parseArgs(argsJson), ctx.workspaceId(), timeoutMs, ctx.permissionMode()));

            if (!entry.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                log.warn("[ToolChannel] 等待客户端结果超时 callId={} tool={} timeoutMs={}", callId, toolName, timeoutMs);
                return error(ErrorCode.TOOL_TIMEOUT, "工具执行超时（" + (timeoutMs / 1000) + "s 未返回），已按失败处理");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(ErrorCode.TOOL_DISCONNECTED, "工具执行被中断");
        } finally {
            // 无论成功失败都必须摘掉，否则 map 随会话数量线性增长
            pending.remove(callId);
        }

        if (entry.result == null) {
            return error(entry.cancelled ? ErrorCode.TOOL_DISCONNECTED : ErrorCode.TOOL_TIMEOUT,
                    entry.cancelled ? "客户端连接已断开，工具未执行" : "工具未返回结果");
        }

        ToolResultRequest r = entry.result;
        if (!r.isOk()) {
            String msg = r.getError() == null || r.getError().isBlank() ? "工具执行失败" : r.getError();
            String code = r.getMeta() != null && r.getMeta().getErrorCode() != null
                    ? r.getMeta().getErrorCode() : "TOOL_FAILED";
            return error(code, msg);
        }

        String content = r.getContent() == null ? "" : r.getContent();
        if (r.isTruncated()) {
            content += "\n\n[输出已截断：超过单次 256KB 上限]";
        }
        return content;
    }

    /**
     * 上行结果解锁（{@code POST /chat/tool-result/{callId}} 调用）。
     *
     * @return 是否命中在途调用；false 表示 callId 未知 / 已超时 / 不属于该用户
     */
    public boolean resolve(String callId, Long userId, ToolResultRequest req) {
        PendingToolCall entry = callId == null ? null : pending.get(callId);
        if (entry == null) {
            log.warn("[ToolChannel] 未找到在途调用或已超时 callId={}", callId);
            return false;
        }
        // 越权防护：只能解锁自己发起的调用
        if (userId == null || !userId.equals(entry.userId)) {
            log.warn("[ToolChannel] callId 归属不匹配，拒绝解锁 callId={} caller={} owner={}",
                    callId, userId, entry.userId);
            return false;
        }
        entry.result = req == null ? new ToolResultRequest() : req;
        entry.latch.countDown();
        log.info("[ToolChannel] 已解锁 callId={} ok={}", callId, entry.result.isOk());
        return true;
    }

    /**
     * 释放某用户的全部在途调用（SSE 流终止 / 用户停止时调用）。
     *
     * <p>断线时下游已经收不到事件了，所以这里只广播一次尽力的 {@code tool_cancel}
     * （客户端据此终止还在跑的子进程），然后唤醒等待线程让 Agent 循环正常收尾。</p>
     */
    public void releaseAllForUser(Long userId, Sinks.Many<ServerSentEvent<String>> sink) {
        if (userId == null) return;
        List<String> ids = new ArrayList<>();
        for (PendingToolCall e : pending.values()) {
            if (userId.equals(e.userId)) ids.add(e.callId);
        }
        for (String callId : ids) {
            PendingToolCall e = pending.remove(callId);
            if (e == null) continue;
            e.cancelled = true;
            if (sink != null) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("callId", callId);
                emit(sink, "tool_cancel", payload);
            }
            e.latch.countDown();
        }
        if (!ids.isEmpty()) {
            log.info("[ToolChannel] 已释放 {} 个在途工具调用（userId={}）", ids.size(), userId);
        }
    }

    /** 在途调用数（健康检查 / 排查线程泄漏用） */
    public int inFlight() {
        return pending.size();
    }

    /** 参数解析失败时原样当字符串传下去，让客户端看到问题而不是静默拿到空参数 */
    private Object parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(argsJson, Map.class);
        } catch (Exception e) {
            log.warn("[ToolChannel] 参数不是合法 JSON，按原文下发: {}", argsJson);
            return argsJson;
        }
    }

    /** 交给模型看的结构化错误（模型读得懂、日志也能按码分类） */
    private String error(String code, String message) {
        return "ERROR [" + code + "]: " + message;
    }

    private void emit(Sinks.Many<ServerSentEvent<String>> sink, String event, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            Sinks.EmitResult r = sink.tryEmitNext(ServerSentEvent.<String>builder().event(event).data(json).build());
            if (r.isFailure()) log.warn("[ToolChannel] 事件发送失败 event={} reason={}", event, r);
        } catch (Exception e) {
            log.warn("[ToolChannel] 事件序列化失败 event={}", event, e);
        }
    }
}

package com.lumenai.backend.tool.interaction;

import com.lumenai.backend.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * AskUserQuestion — 向用户提问并等待回答
 * <p>
 * 通过回调发射 SSE question 事件到前端，前端弹框让用户回答，
 * 用户回答后 POST 回后端解锁等待。
 */
@Slf4j
@Component
public class AskUserQuestionTool implements Tool {

    /** 等待用户回答的映射：requestId → (latch, answer) */
    private static final Map<String, QuestionPending> pendingQuestions = new ConcurrentHashMap<>();

    private static class QuestionPending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String answer = "";
    }

    /** SSE 发送回调，由 AgentEngine 在会话开始时注入 */
    private volatile BiConsumer<String, Map<String, Object>> sseEmitter;

    @Override public String name() { return "AskUserQuestion"; }
    @Override public String description() { return "Ask the user a question and wait for their response. Use when you need clarification or user input to proceed."; }
    @Override public Permission requiredPermission() { return Permission.AUTO_ALLOW; }
    @Override public boolean isReadOnly() { return true; }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new HashMap<>();
        props.put("question", Map.of("type", "string", "description", "The question to ask the user"));
        props.put("options", Map.of("type", "array", "description", "Optional list of choices for the user to pick from"));
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("question"));
        return schema;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "AskUserQuestion",
            description = "Ask the user a question and wait for their response. Use when you need clarification or user input to proceed."
    )
    public String ask(
            @ToolParam(description = "The question to ask the user") String question,
            @ToolParam(description = "Optional list of choices for the user to pick from") List<String> options) {
        long start = System.currentTimeMillis();

        String requestId = UUID.randomUUID().toString();
        QuestionPending pending = new QuestionPending();
        pendingQuestions.put(requestId, pending);

        if (sseEmitter != null) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("requestId", requestId);
                payload.put("question", question);
                if (options != null && !options.isEmpty()) {
                    payload.put("options", options);
                }
                sseEmitter.accept("question", payload);
                log.info("[AskUserQuestion] sent question, requestId={}", requestId);
            } catch (Exception e) {
                log.error("[AskUserQuestion] failed to emit question event", e);
                pendingQuestions.remove(requestId);
                return "ERROR: failed to send question to user";
            }
        } else {
            pendingQuestions.remove(requestId);
            return "ERROR: no active SSE connection to send question";
        }

        // 阻塞等待用户回答，最多 5 分钟
        try {
            if (!pending.latch.await(5, TimeUnit.MINUTES)) {
                log.warn("[AskUserQuestion] timeout, requestId={}", requestId);
                pendingQuestions.remove(requestId);
                return "(user did not respond within 5 minutes)";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendingQuestions.remove(requestId);
            return "(interrupted)";
        }

        pendingQuestions.remove(requestId);
        String answer = pending.answer;
        log.info("[AskUserQuestion] got answer for requestId={}, {}ms", requestId, System.currentTimeMillis() - start);
        return answer.isEmpty() ? "(user skipped)" : answer;
    }

    /** 前端回答后调用此方法解锁 */
    public void resolveQuestion(String requestId, String answer) {
        QuestionPending pending = pendingQuestions.get(requestId);
        if (pending == null) {
            log.warn("[AskUserQuestion] no pending question for requestId={}", requestId);
            return;
        }
        pending.answer = answer != null ? answer : "";
        pending.latch.countDown();
        log.info("[AskUserQuestion] resolved requestId={}", requestId);
    }

    /** 设置 SSE 发送回调（由 AgentEngine 在会话开始时调用） */
    public void setSseEmitter(BiConsumer<String, Map<String, Object>> emitter) {
        this.sseEmitter = emitter;
    }

    @Override
    public ToolResult execute(ToolContext ctx, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        try {
            @SuppressWarnings("unchecked")
            List<String> options = (List<String>) args.get("options");
            String output = ask((String) args.get("question"), options);
            if (output.startsWith("ERROR:")) {
                return ToolResult.fail(output, (int) (System.currentTimeMillis() - start));
            }
            return ToolResult.ok(output, (int) (System.currentTimeMillis() - start));
        } catch (Exception e) {
            log.error("AskUserQuestion tool error", e);
            return ToolResult.fail(e.getMessage(), (int) (System.currentTimeMillis() - start));
        }
    }
}

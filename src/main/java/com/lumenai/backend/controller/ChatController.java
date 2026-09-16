package com.lumenai.backend.controller;

import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.agent.core.AgentEngine;
import com.lumenai.backend.dto.ChatMessageRequest;
import com.lumenai.backend.dto.ChatRequest;
import com.lumenai.backend.dto.SessionCreateRequest;
import com.lumenai.backend.dto.SessionUpdateRequest;
import com.lumenai.backend.entity.Message;
import com.lumenai.backend.entity.Session;
import com.lumenai.backend.mapper.MessageMapper;
import com.lumenai.backend.service.ScheduleRunService;
import com.lumenai.backend.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;

import com.lumenai.backend.common.response.R;

/** 聊天 SSE 接口 */
@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AgentEngine agentEngine;
    private final SessionService sessionService;
    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final ScheduleRunService scheduleRunService;

    /** 发送消息并流式返回 AI 回复 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest req) {
        Long userId = UserContext.get();
        Long sessionId = req.getSessionId();

        // 没有 sessionId 就自动创建
        if (sessionId == null) {
            Session created = sessionService.create(userId, new SessionCreateRequest());
            sessionId = created.getId();
        }
        Session session = sessionService.getOrThrow(userId, sessionId);

        // 1. 先存 user message
        Message userMsg = new Message();
        userMsg.setSessionId(sessionId);
        userMsg.setUserId(userId);
        userMsg.setRole("user");
        userMsg.setContent(req.getContent());
        // 附件序列化成 JSON 存 DB
        if (req.getAttachments() != null && !req.getAttachments().isEmpty()) {
            try {
                userMsg.setAttachments(objectMapper.writeValueAsString(req.getAttachments()));
            } catch (Exception e) {
                log.warn("序列化 attachments 失败", e);
            }
        }
        userMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(userMsg);

        // 2. 首条消息自动更新 session title（取前 20 字）
        if ("新会话".equals(session.getTitle()) && req.getContent() != null) {
            String title = req.getContent().length() > 20
                    ? req.getContent().substring(0, 20) + "..."
                    : req.getContent();
            SessionUpdateRequest update = new SessionUpdateRequest();
            update.setTitle(title);
            sessionService.update(userId, sessionId, update);
        }

        ChatMessageRequest engineReq = new ChatMessageRequest();
        engineReq.setContent(req.getContent());
        engineReq.setAttachments(req.getAttachments());
        log.info("[ChatController] attachments received: count={}, content={}",
                req.getAttachments() == null ? 0 : req.getAttachments().size(),
                req.getAttachments());

        Long finalSessionId = sessionId;
        Long finalUserId = userId;
        Long scheduleRunId = req.getScheduleRunId();

        // 手动触发的执行记录关联 sessionId
        if (scheduleRunId != null) {
            try {
                scheduleRunService.updateSessionId(scheduleRunId, finalSessionId);
            } catch (Exception e) {
                log.warn("更新执行记录 sessionId 失败 runId={}", scheduleRunId, e);
            }
        }

        Flux<ServerSentEvent<String>> flux = agentEngine.run(session, engineReq);

        // 3. 解析 done 事件拿 finalContent + metadata，存 assistant message
        return flux.doOnNext(event -> {
            if ("done".equals(event.event()) && event.data() != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = objectMapper.readValue(event.data(), Map.class);
                    String finalContent = (String) payload.getOrDefault("finalContent", "");

                    Message assistantMsg = new Message();
                    assistantMsg.setSessionId(finalSessionId);
                    assistantMsg.setUserId(finalUserId);
                    assistantMsg.setRole("assistant");
                    assistantMsg.setContent(finalContent);
                    assistantMsg.setCreatedAt(LocalDateTime.now());

                    // 写入元数据
                    assistantMsg.setModel((String) payload.getOrDefault("model", ""));

                    Object cancelled = payload.get("cancelled");
                    if (cancelled instanceof Boolean b) assistantMsg.setCancelled(b);

                    Object latencyMs = payload.get("latencyMs");
                    if (latencyMs instanceof Number n) assistantMsg.setLatencyMs(n.intValue());

                    Object toolCalls = payload.get("toolCalls");
                    if (toolCalls != null) {
                        String tcJson = objectMapper.writeValueAsString(toolCalls);
                        assistantMsg.setToolCalls(tcJson);
                    }

                    Object usage = payload.get("usage");
                    if (usage instanceof Map<?, ?> um) {
                        Object in = um.get("input_tokens");
                        Object out = um.get("output_tokens");
                        if (in instanceof Number n) assistantMsg.setInputTokens(n.intValue());
                        if (out instanceof Number n) assistantMsg.setOutputTokens(n.intValue());
                    }

                    messageMapper.insert(assistantMsg);

                    // 更新自动化任务执行历史状态为 success
                    if (scheduleRunId != null) {
                        try {
                            String summary = finalContent.length() > 200
                                    ? finalContent.substring(0, 200) + "..."
                                    : finalContent;
                            scheduleRunService.completeRun(scheduleRunId, "success", summary, null);
                        } catch (Exception e) {
                            log.warn("更新执行历史状态失败 runId={}", scheduleRunId, e);
                        }
                    }

                    log.info("Saved assistant message, sessionId={}, len={}, model={}, tokens={}/{}, latency={}ms, tools={}",
                            finalSessionId, finalContent.length(),
                            assistantMsg.getModel(),
                            assistantMsg.getInputTokens(), assistantMsg.getOutputTokens(),
                            assistantMsg.getLatencyMs(),
                            assistantMsg.getToolCalls() != null ? "yes" : "no");
                } catch (Exception e) {
                    log.error("Failed to save assistant message", e);
                }
            } else if ("error".equals(event.event()) && scheduleRunId != null) {
                // 出错时标记执行历史为 failed
                try {
                    String errMsg = event.data() != null ? event.data() : "unknown error";
                    scheduleRunService.completeRun(scheduleRunId, "failed", null, errMsg);
                } catch (Exception e) {
                    log.warn("更新执行历史失败状态失败 runId={}", scheduleRunId, e);
                }
            }
        });
    }

    /** 接收用户对权限请求的确认/拒绝 */
    @PostMapping("/permission/{requestId}")
    public R<Void> resolvePermission(@PathVariable String requestId, @RequestBody Map<String, Boolean> body) {
        Boolean approved = body.get("approved");
        if (approved == null) {
            return R.fail(400, "missing 'approved' field");
        }
        agentEngine.resolvePermission(requestId, approved);
        return R.ok();
    }

    /** 接收用户对 AskUserQuestion 的回答 */
    @PostMapping("/question/{requestId}")
    public R<Void> resolveQuestion(@PathVariable String requestId, @RequestBody Map<String, String> body) {
        String answer = body.get("answer");
        if (answer == null) {
            answer = "";
        }
        agentEngine.resolveQuestion(requestId, answer);
        return R.ok();
    }
}

package com.lumenai.backend.controller;

import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.agent.core.AgentEngine;
import com.lumenai.backend.dto.BillingUsage;
import com.lumenai.backend.dto.ChatMessageRequest;
import com.lumenai.backend.dto.ChatRequest;
import com.lumenai.backend.dto.CreditsInfoVO;
import com.lumenai.backend.dto.SessionCreateRequest;
import com.lumenai.backend.dto.SessionUpdateRequest;
import com.lumenai.backend.dto.ToolResultRequest;
import com.lumenai.backend.entity.CreditHold;
import com.lumenai.backend.entity.Message;
import com.lumenai.backend.entity.Session;
import com.lumenai.backend.mapper.MessageMapper;
import com.lumenai.backend.service.ChatBillingService;
import com.lumenai.backend.service.ClientToolGateway;
import com.lumenai.backend.service.ScheduleRunService;
import com.lumenai.backend.service.SessionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.lumenai.backend.common.response.R;

/**
 * 聊天 SSE 接口
 *
 * <p>积分扣费走<b>两阶段</b>，三个阶段分别落在本类的三个位置：</p>
 * <ol>
 *   <li><b>预扣</b>：调模型之前（余额不够直接拒绝，连用户消息都不落库）；</li>
 *   <li><b>结算</b>：{@code done} 事件处（按真实 usage 多退少补，并把 {@code credits} 附回事件给前端）；</li>
 *   <li><b>退款/兜底</b>：{@code doFinally}（客户端点「停止」时下游拿不到 done 事件，
 *       只能在这里按用量快照结算；零产出则全额退款）。</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    /** 系统提示词 + 工具 schema 的固定输入开销（字符），预扣估算用 */
    private static final int SYSTEM_PROMPT_CHARS = 4000;

    /** 估算输入时最多回看多少条历史消息（与 AgentEngine 取历史的量级一致） */
    private static final int HISTORY_LIMIT = 50;

    private final AgentEngine agentEngine;
    private final SessionService sessionService;
    private final MessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    private final ScheduleRunService scheduleRunService;
    private final ChatBillingService chatBillingService;
    private final ClientToolGateway clientToolGateway;

    /** 发送消息并流式返回 AI 回复 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest req,
                                                @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        Long userId = UserContext.get();
        Long sessionId = req.getSessionId();

        // 没有 sessionId 就自动创建
        if (sessionId == null) {
            Session created = sessionService.create(userId, new SessionCreateRequest(), deviceId);
            sessionId = created.getId();
        }
        Session session = sessionService.getOrThrow(userId, sessionId);

        // 顺手清扫超时未结算的预扣单（与积分按月发放同一套「挂在必经入口」的思路，不引定时任务）
        safeSweep(userId);

        ChatMessageRequest engineReq = new ChatMessageRequest();
        engineReq.setContent(req.getContent());
        engineReq.setAttachments(req.getAttachments());
        // 设备维度 + 项目约定（LUMEN.md）随请求进来：前者决定本地类工具下不下发，后者进系统提示词
        engineReq.setDeviceId(deviceId);
        engineReq.setProjectConvention(req.getProjectConvention());
        log.info("[ChatController] attachments received: count={}, content={}",
                req.getAttachments() == null ? 0 : req.getAttachments().size(),
                req.getAttachments());

        // ===== 阶段 1：预扣（模型还没调，先把钱按估算扣下来）=====
        String requestId = ChatBillingService.newRequestId();
        long estimatedInput = estimateInputTokens(sessionId, req.getContent());
        ChatBillingService.HoldResult hold;
        try {
            hold = chatBillingService.hold(userId, requestId, sessionId, CreditHold.CHANNEL_WEB,
                    agentEngine.modelCodeFor(engineReq), estimatedInput);
        } catch (Exception e) {
            log.error("预扣失败 userId={} sessionId={}", userId, sessionId, e);
            return Flux.just(serverEvent("error",
                    Map.of("code", "BILLING_ERROR", "message", "积分扣费失败，请稍后重试")));
        }
        if (hold.insufficient()) {
            log.info("积分不足，拒绝本次请求 userId={} 需要={} 现有={}", userId, hold.required(), hold.balanceAfter());
            return Flux.just(serverEvent("error",
                    Map.of("code", ChatBillingService.NO_CREDITS, "message", noCreditsMessage(hold))));
        }

        Long finalSessionId = sessionId;
        Long finalUserId = userId;
        Long scheduleRunId = req.getScheduleRunId();

        // 1. 先存 user message
        Message userMsg = new Message();
        userMsg.setSessionId(finalSessionId);
        userMsg.setUserId(finalUserId);
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
            sessionService.update(finalUserId, finalSessionId, update);
        }

        // 手动触发的执行记录关联 sessionId
        if (scheduleRunId != null) {
            try {
                scheduleRunService.updateSessionId(scheduleRunId, finalSessionId);
            } catch (Exception e) {
                log.warn("更新执行记录 sessionId 失败 runId={}", scheduleRunId, e);
            }
        }

        // 流水标题：用「本轮用户提问」，不用会话标题（会话标题只是首问，第 2 轮起会张冠李戴）
        String recordTitle = recordTitleOf(req.getContent());

        // ===== 阶段 2/3：用量快照 + 结算/退款 =====
        BillingUsage usage = new BillingUsage();
        AtomicBoolean settled = new AtomicBoolean(false);
        Flux<ServerSentEvent<String>> flux = agentEngine.run(session, engineReq, usage);

        return flux
                .map(event -> handleEvent(event, finalUserId, finalSessionId, requestId, recordTitle,
                        usage, hold, settled, scheduleRunId))
                .doFinally(signal -> {
                    finalizeIfNeeded(finalUserId, requestId, recordTitle, usage, hold, settled, signal);
                    // 流终止（用户点停止 / 客户端退出 / 异常）：唤醒所有还在等客户端结果的工具调用，
                    // 否则等待的是 Agent 循环线程，会一直悬挂到 deadline（线程泄漏 + 流不结束）
                    releasePendingToolCalls(finalUserId, finalSessionId);
                });
    }

    /** 释放该用户在途的工具调用等待（通道侧兜底，见 ClientToolGateway#releaseAllForUser） */
    private void releasePendingToolCalls(Long userId, Long sessionId) {
        try {
            clientToolGateway.releaseAllForUser(userId, null);
        } catch (Exception e) {
            log.warn("释放在途工具调用失败 userId={} sessionId={}", userId, sessionId, e);
        }
    }

    /**
     * 逐事件处理：done 事件要「存 assistant message + 结算 + 把 credits 附回事件」。
     *
     * <p>用 {@code map} 而不是 {@code doOnNext}，是因为结算结果必须写进前端收到的那条 done 事件里
     * （前端据此回写余额、展示本次消耗），侧效应式的 doOnNext 改不了已发出的事件。</p>
     */
    private ServerSentEvent<String> handleEvent(ServerSentEvent<String> event, Long userId, Long sessionId,
                                                String requestId, String recordTitle, BillingUsage usage,
                                                ChatBillingService.HoldResult hold, AtomicBoolean settled,
                                                Long scheduleRunId) {
        if ("done".equals(event.event()) && event.data() != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = objectMapper.readValue(event.data(), Map.class);
                String finalContent = (String) payload.getOrDefault("finalContent", "");

                Message assistantMsg = new Message();
                assistantMsg.setSessionId(sessionId);
                assistantMsg.setUserId(userId);
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

                Object usageNode = payload.get("usage");
                if (usageNode instanceof Map<?, ?> um) {
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
                        sessionId, finalContent.length(),
                        assistantMsg.getModel(),
                        assistantMsg.getInputTokens(), assistantMsg.getOutputTokens(),
                        assistantMsg.getLatencyMs(),
                        assistantMsg.getToolCalls() != null ? "yes" : "no");

                // ===== 结算 =====
                CreditsInfoVO credits = settleOrRefund(userId, requestId, recordTitle, usage, hold, settled,
                        assistantMsg.getId());
                if (credits != null) {
                    // 结算值冗余回消息行：历史重载时气泡 footer 直接可读，不必反查流水、前端也不自算
                    messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                            .eq(Message::getId, assistantMsg.getId())
                            .set(Message::getCreditsCost, credits.getCost())
                            .set(Message::getCreditsEstimated, credits.isEstimated()));
                    Map<String, Object> creditsNode = new LinkedHashMap<>();
                    creditsNode.put("cost", credits.getCost());
                    creditsNode.put("preConsumed", credits.getPreConsumed());
                    creditsNode.put("refunded", credits.getRefunded());
                    creditsNode.put("balanceAfter", credits.getBalanceAfter());
                    creditsNode.put("estimated", credits.isEstimated());
                    payload.put("credits", creditsNode);
                    return serverEvent("done", payload);
                }
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
        return event;
    }

    /**
     * 流终止（正常 / 取消 / 异常）时的兜底结算。
     *
     * <p>用户点「停止」会断开 SSE 连接，此时 {@code done} 事件到不了下游 —— 如果只在 done 处结算，
     * 取消的对话就白跑了。这里按 AgentEngine 留下的用量快照如实结算（内容为空则全额退款）。
     * 已结算过的一律跳过（幂等）。</p>
     */
    private void finalizeIfNeeded(Long userId, String requestId, String recordTitle, BillingUsage usage,
                                  ChatBillingService.HoldResult hold, AtomicBoolean settled,
                                  reactor.core.publisher.SignalType signal) {
        if (settled.get() || hold == null || hold.free() || !hold.billed()) {
            return;
        }
        try {
            log.info("[stream {}] 兜底结算 userId={} 已产出字符={} tokens={}",
                    signal, userId, usage.getContentLength(), usage.totalTokens());
            settleOrRefund(userId, requestId, recordTitle, usage, hold, settled, null);
        } catch (Exception e) {
            // 兜底失败不吞异常地留下痕迹：预扣单仍是 held，10 分钟后由惰性清扫退款
            log.warn("兜底结算失败，预扣单将由惰性清扫退款 userId={} requestId={}", userId, requestId, e);
        }
    }

    /**
     * 结算或退款：有产出 → 按真实用量结算（多退少补）；零产出 → 全额退款。
     *
     * @param messageId 结算归属的 assistant message（断连路径拿不到，传 null）
     */
    private CreditsInfoVO settleOrRefund(Long userId, String requestId, String recordTitle, BillingUsage usage,
                                        ChatBillingService.HoldResult hold, AtomicBoolean settled, Long messageId) {
        if (hold == null || hold.free() || !hold.billed()) {
            settled.set(true);
            return null;
        }
        if (usage.getContentLength() <= 0) {
            chatBillingService.refund(userId, requestId, "零产出（上游报错或模型无输出）");
            settled.set(true);
            return null;
        }
        CreditsInfoVO credits = chatBillingService.settle(userId, requestId, usage.getModel(), usage,
                messageId, recordTitle);
        settled.set(true);
        return credits;
    }

    /** 惰性清扫（失败不影响聊天主流程） */
    private void safeSweep(Long userId) {
        try {
            chatBillingService.sweepExpiredHolds(userId);
        } catch (Exception e) {
            log.warn("清扫僵尸预扣单失败 userId={}", userId, e);
        }
    }

    /**
     * 预扣用的输入 token 估算：本次待发文本（历史消息 + 系统提示 + 用户消息）字符数 ÷ 4。
     *
     * <p>粗算即可 —— 预扣多算了会退、少算了会补，估算只决定「能不能发起本次请求」的门槛。</p>
     */
    private long estimateInputTokens(Long sessionId, String content) {
        long chars = content == null ? 0 : content.length();
        try {
            List<Message> history = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                    .eq(Message::getSessionId, sessionId)
                    .orderByDesc(Message::getId)
                    .last("LIMIT " + HISTORY_LIMIT));
            for (Message m : history) {
                if (m.getContent() != null) {
                    chars += m.getContent().length();
                }
            }
        } catch (Exception e) {
            log.warn("估算输入 token 时读取历史失败 sessionId={}", sessionId, e);
        }
        return ChatBillingService.estimateTokens(chars + SYSTEM_PROMPT_CHARS);
    }

    /**
     * 流水标题 = <b>本轮用户提问</b>。
     *
     * <p>为什么不用会话标题：会话标题只由首条消息生成，一个会话问上三五轮时，
     * 后面每一轮的流水都会挂着第一句话 —— 明细页看起来像「同一条记录重复了很多行」，
     * 对不上账。办公助理通道（{@code ChannelReplyService}）本来就用当轮消息当标题，
     * Web 聊天与它保持一致。</p>
     *
     * <p>折叠空白、超长截断、空值兜底由 {@code ChatBillingService#titleOf} 统一处理，
     * 这里只负责交出原文。</p>
     */
    private String recordTitleOf(String content) {
        return content == null ? null : content.trim();
    }

    /** 余额不足的提示文案：带上「应需 / 现有」两个数字，用户能自己判断要充多少 */
    private String noCreditsMessage(ChatBillingService.HoldResult hold) {
        BigDecimal balance = hold.balanceAfter() == null ? BigDecimal.ZERO : hold.balanceAfter();
        // 无论余额是否为 0，都如实带上「应需 / 现有」——数字比「积分已用尽」更能解释为什么被拦
        String head = balance.signum() <= 0 ? "积分已用尽。" : "";
        return head + "本次请求预计需要 " + hold.required() + " 积分，当前可用 "
                + balance.stripTrailingZeros().toPlainString() + " 积分，请购买或升级套餐";
    }

    private ServerSentEvent<String> serverEvent(String name, Map<String, ?> payload) {
        try {
            return ServerSentEvent.<String>builder()
                    .event(name)
                    .data(objectMapper.writeValueAsString(payload))
                    .build();
        } catch (Exception e) {
            return ServerSentEvent.<String>builder().event(name).data("{}").build();
        }
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

    /**
     * 客户端回传本地工具的执行结果（工具执行通道的上行端点）。
     *
     * <p>与被照抄的 {@code /permission/{requestId}} 同构：服务端阻塞等结果，
     * 这里 countDown 解锁。🔴 <b>必须校验 callId 归属当前用户</b> ——
     * 否则任何人拿到一个 callId 就能解锁别人的工具调用（越权投毒）。</p>
     */
    @PostMapping("/tool-result/{callId}")
    public R<Void> resolveToolResult(@PathVariable String callId, @RequestBody ToolResultRequest body) {
        boolean ok = clientToolGateway.resolve(callId, UserContext.get(), body);
        if (!ok) {
            // 已经超时 / 断线 / 不属于本人：不是致命错误，客户端无需重试
            return R.fail(404, "工具调用不存在或已超时");
        }
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

package com.lumenai.backend.controller;

import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.common.response.R;
import com.lumenai.backend.entity.ChannelAccount;
import com.lumenai.backend.entity.ChannelMessage;
import com.lumenai.backend.service.ChannelReplyService;
import com.lumenai.backend.service.ReplyStreamRegistry;
import com.lumenai.backend.service.WorkAssistantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 办公助理（iLink Bot）Controller
 *
 * 架构：Electron 主进程直连 iLink HTTP API，后端只做数据落库
 *   Electron 登录成功 → POST /accounts/confirm
 *   Electron 收到微信消息 → POST /messages/inbound
 *   前端展示 → GET /accounts, GET /messages, GET /sessions
 *   前端发消息 → POST /messages（入库） + Electron IPC（实际发送）
 */
@RestController
@RequestMapping("/work-assistant")
@RequiredArgsConstructor
@Slf4j
public class WorkAssistantController {

    private final WorkAssistantService service;
    private final ReplyStreamRegistry registry;
    private final ChannelReplyService replyService;
    private final ObjectMapper objectMapper;

    // === 通道账号 ===

    /** 列出当前用户所有通道绑定 */
    @GetMapping("/accounts")
    public R<List<ChannelAccount>> listAccounts() {
        return R.ok(service.listAccounts());
    }

    /** 查询某通道绑定状态（顺带主动校验 token 是否仍有效，失效则自动标记解绑） */
    @GetMapping("/accounts/{channel}")
    public R<ChannelAccount> getAccount(@PathVariable String channel) {
        service.verifyBinding(channel);
        return R.ok(service.getAccount(channel));
    }

    /**
     * 确认绑定完成（Electron 主进程调 iLink API 扫码成功后回调）
     * 支持两种模式：
     *   - 有 JWT：userId 从 UserContext 拿
     *   - 无 JWT：请求体必须传 userId（Electron 主进程直调）
     */
    @PostMapping("/accounts/confirm")
    public R<ChannelAccount> confirmBind(@RequestBody Map<String, String> body) {
        String channel = body.get("channel");
        String botToken = body.get("botToken");
        String ilinkBotId = body.get("ilinkBotId");
        String ilinkUserId = body.get("ilinkUserId");
        String baseUrl = body.get("baseUrl");
        String userIdStr = body.get("userId");
        if (channel == null || botToken == null || ilinkBotId == null) {
            return R.fail("缺少必要参数");
        }
        Long userId = null;
        if (userIdStr != null) {
            try { userId = Long.valueOf(userIdStr); } catch (Exception ignored) {}
        }
        if (userId == null) {
            try { userId = UserContext.get(); } catch (Exception ignored) {}
        }
        if (userId == null) {
            return R.fail("无法识别用户：请传 userId 或携带 JWT");
        }
        return R.ok(service.confirmBind(userId, channel, botToken, ilinkBotId, ilinkUserId, baseUrl));
    }

    /** 解绑通道 */
    @DeleteMapping("/accounts/{channel}")
    public R<Void> unbind(@PathVariable String channel) {
        service.unbind(channel);
        return R.ok(null);
    }

    /**
     * 标记会话失效（Electron 主进程检测到 iLink ret=-14 时调用）。
     * 场景：同一微信账号在其它平台重新扫码绑定 ClawBot，本机 bot_token 被顶下线。
     * 无 JWT，按 ilinkUserId 反查账号。
     */
    @PostMapping("/accounts/session-expired")
    public R<Void> markSessionExpired(@RequestBody Map<String, String> body) {
        String ilinkUserId = body.get("ilinkUserId");
        if (ilinkUserId == null || ilinkUserId.isBlank()) {
            return R.fail("ilinkUserId 不能为空");
        }
        service.markSessionExpired(ilinkUserId);
        return R.ok(null);
    }

    // === 消息 ===

    /** 列出通道消息（按当前登录用户 + weixin 通道查） */
    @GetMapping("/messages")
    public R<List<ChannelMessage>> listMessages(
            @RequestParam(required = false) String peerId) {
        List<ChannelMessage> msgs = service.listCurrentUserMessages(peerId);
        return R.ok(msgs);
    }

    /** 列出通道会话列表 */
    @GetMapping("/sessions")
    public R<List<ChannelMessage>> listSessions() {
        return R.ok(service.listCurrentUserSessions());
    }

    /**
     * 写入 inbound 消息（Electron 主进程收到微信消息后回调）
     * 支持两种模式：
     *   - 带 JWT 认证：Electron 走后端，自动关联当前用户
     *   - 无认证（noAuth=true）：Electron 直接调，通过 ilinkUserId 反查
     */
    @PostMapping("/messages/inbound")
    public R<ChannelMessage> saveInboundMessage(@RequestBody Map<String, String> body) {
        String channel = body.get("channel");
        String peerId = body.get("peerId");
        String content = body.get("content");
        String ilinkMsgId = body.get("ilinkMsgId");
        String contextToken = body.get("contextToken");
        String ilinkUserId = body.get("ilinkUserId");
        String noAuth = body.get("noAuth");

        if (channel == null || peerId == null || content == null) {
            return R.fail("缺少必要参数");
        }

        ChannelMessage msg;
        if ("true".equals(noAuth) && ilinkUserId != null) {
            msg = service.saveInboundMessageNoAuth(channel, ilinkUserId, peerId, content, ilinkMsgId, contextToken);
        } else {
            msg = service.saveInboundMessage(channel, peerId, content, ilinkMsgId, contextToken);
        }
        return R.ok(msg);
    }

    /**
     * 发送消息（前端 → 后端入库 → Electron 实际发送）
     * 后端只做入库，实际调 iLink API 由前端通过 Electron IPC 完成
     */
    @PostMapping("/messages")
    public R<ChannelMessage> sendMessage(@RequestBody Map<String, Object> req) {
        Long accountId = Long.valueOf(req.get("accountId").toString());
        String peerId = (String) req.get("peerId");
        String content = (String) req.get("content");
        return R.ok(service.saveOutboundMessage(accountId, peerId, content));
    }

    /** 标记已读 */
    @PostMapping("/messages/read")
    public R<Void> markRead(@RequestBody Map<String, Object> req) {
        Long accountId = Long.valueOf(req.get("accountId").toString());
        String peerId = (String) req.get("peerId");
        service.markRead(accountId, peerId);
        return R.ok(null);
    }

    // === AI 回复流式订阅（SSE） ===

    /**
     * 重新生成某会话最近一条 AI 回复。
     * 只重跑模型，不把用户消息再发一次到微信；前端调用后订阅 /reply/stream 拿流式结果。
     */
    @PostMapping("/reply/regenerate")
    public R<Void> regenerateReply(@RequestBody Map<String, Object> req) {
        Long accountId = Long.valueOf(req.get("accountId").toString());
        String peerId = (String) req.get("peerId");
        ChannelAccount account = service.getOwnedAccount(UserContext.get(), accountId);
        if (account == null) {
            return R.fail(403, "无权访问该会话");
        }
        ChannelMessage userMessage = service.findLastUserMessage(accountId, peerId);
        if (userMessage == null) {
            return R.fail("没有可重新生成的提问");
        }
        replyService.regenerateAsync(account, userMessage);
        return R.ok(null);
    }

    /**
     * 订阅某个会话的 AI 流式回复。
     * 事件：
     *   delta {"text": "<累计全文>"}  —— 内容增长，前端整体替换
     *   done  {"error": null}         —— 结束（最终消息已入库，前端重新拉取）
     *   idle  {}                      —— 当前没有进行中的回复
     */
    @GetMapping(value = "/reply/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamReply(@RequestParam Long accountId, @RequestParam String peerId) {
        Long userId = UserContext.get();
        SseEmitter emitter = new SseEmitter(120_000L);

        ChannelAccount account = service.getOwnedAccount(userId, accountId);
        if (account == null) {
            try {
                emitter.send(SseEmitter.event().name("done").data("{\"error\":\"无权访问该会话\"}"));
            } catch (Exception ignored) {}
            emitter.complete();
            return emitter;
        }

        final String key = ReplyStreamRegistry.key(accountId, peerId);
        Thread worker = new Thread(() -> {
            try {
                long lastVersion = -1;
                long deadline = System.currentTimeMillis() + 100_000;
                long waitStateUntil = System.currentTimeMillis() + 3_000; // 等 3 秒让后端回复任务启动

                while (System.currentTimeMillis() < deadline) {
                    ReplyStreamRegistry.State state = registry.get(key);
                    if (state == null) {
                        if (System.currentTimeMillis() < waitStateUntil) {
                            Thread.sleep(150);
                            continue;
                        }
                        emitter.send(SseEmitter.event().name("idle").data("{}"));
                        emitter.complete();
                        return;
                    }

                    String snapshot;
                    boolean done;
                    String error;
                    long version;
                    synchronized (state) {
                        version = state.getVersion();
                        snapshot = state.snapshot();
                        done = state.isDone();
                        error = state.getError();
                    }
                    if (version != lastVersion) {
                        lastVersion = version;
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("text", snapshot);
                        emitter.send(SseEmitter.event().name("delta")
                                .data(objectMapper.writeValueAsString(payload)));
                    }
                    if (done) {
                        Map<String, Object> donePayload = new LinkedHashMap<>();
                        donePayload.put("error", error);
                        donePayload.put("inputTokens", state.getInputTokens());
                        donePayload.put("outputTokens", state.getOutputTokens());
                        emitter.send(SseEmitter.event().name("done")
                                .data(objectMapper.writeValueAsString(donePayload)));
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(150);
                }
                emitter.complete();
            } catch (Exception e) {
                log.debug("reply SSE 结束: {}", e.getMessage());
                emitter.completeWithError(e);
            }
        }, "reply-sse-" + accountId);
        worker.setDaemon(true);
        worker.start();

        emitter.onTimeout(emitter::complete);
        return emitter;
    }
}

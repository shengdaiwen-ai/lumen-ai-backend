package com.lumenai.backend.agent.core;

import com.lumenai.backend.dto.AttachmentDto;
import tools.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumenai.backend.agent.tool.ToolRegistry;
import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.config.LumenProperties;
import com.lumenai.backend.dto.ChatMessageRequest;
import com.lumenai.backend.entity.Session;
import com.lumenai.backend.mapper.MessageMapper;
import com.lumenai.backend.mapper.UserMapper;
import com.lumenai.backend.mapper.PermissionMapper;
import com.lumenai.backend.entity.User;
import com.lumenai.backend.entity.Permission;
import com.lumenai.backend.tool.interaction.AskUserQuestionTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 引擎 — 基于 Spring AI 2.0 的 User-Controlled Tool Execution 模式
 * <p>
 * 自己驱动 ReAct 循环，完全掌控 SSE 事件（text / tool_call_start / tool_call_end / done / error）
 * done 事件携带 finalContent，供 ChatController 持久化
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentEngine {

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final LumenProperties lumenProperties;
    private final MessageMapper messageMapper;
    private final UserMapper userMapper;
    private final PermissionMapper permissionMapper;
    private final AskUserQuestionTool askUserQuestionTool;
    private final ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

    /** 权限审批等待映射：requestId → (latch, approved) */
    private final Map<String, PermissionPending> pendingPermissions = new ConcurrentHashMap<>();

    private static class PermissionPending {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        volatile boolean approved = false;
    }

    /** 当前使用的模型名 — 从配置读，兜底用 deepseek-chat */
    @Value("${spring.ai.deepseek.chat.model:deepseek-chat}")
    private String configuredModel;

    @Value("${spring.ai.deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${spring.ai.deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBaseUrl;

    @Value("${lumen.upload.dir:${user.home}/.lumen-ai/uploads}")
    private String uploadDir;

    /** 上下文 token 阈值 — 超过此值触发压缩（粗略估算：1 中文字≈2 token, 1 英文字≈0.25 token） */
    private static final int CONTEXT_TOKEN_THRESHOLD = 24000;
    /** 压缩后保留的最近消息条数 */
    private static final int KEEP_RECENT_MESSAGES = 6;

    /** 系统提示词 — 从 classpath:system-prompt.txt 读取，避免硬编码 */
    @org.springframework.beans.factory.annotation.Value("classpath:system-prompt.txt")
    private org.springframework.core.io.Resource systemPromptResource;
    private String cachedSystemPrompt;

    private String getSystemPrompt() {
        if (cachedSystemPrompt != null) return cachedSystemPrompt;
        try {
            cachedSystemPrompt = new String(systemPromptResource.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            log.error("Failed to load system-prompt.txt, using fallback", e);
            cachedSystemPrompt = "You are Lumen AI, a helpful AI assistant.";
        }
        return cachedSystemPrompt;
    }

    // ========== 上下文压缩 ==========

    /** 粗略估算消息列表的 token 数 */
    private int estimateTokens(List<Message> messages) {
        int chars = 0;
        for (Message m : messages) {
            String text = m.getText();
            if (text == null) continue;
            for (char c : text.toCharArray()) {
                // 中文字符按 2 token 估算，其他按 0.25
                chars += (c > 127) ? 2 : 1;
            }
        }
        // chars / 4 ≈ 英文 token; 中文已乘 2 所以直接除 4 偏保守
        return chars / 4;
    }

    /**
     * 压缩会话上下文 — 把早期消息用 LLM 总结成一条摘要，保留最近几条原文。/**
     * 处理附件：替换 messages 列表里的最后一条 UserMessage
     * <p>
     * - 图片 → 构造 Spring AI UserMessage 带 Media（多模态识图）
     * - 文本文件（txt/md/json/csv/code 等）→ 读取内容塞 prompt 前面
     * - 其他二进制 → 只加文件名+大小说明
     */
    private void applyAttachments(List<Message> messages, List<AttachmentDto> attachments, Long userId) {
        // 找到最后一条 user message
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx < 0) return;

        UserMessage lastUser = (UserMessage) messages.get(lastUserIdx);
        String userText = lastUser.getText();

        StringBuilder textFileBuffer = new StringBuilder();
        StringBuilder otherBuffer = new StringBuilder();

        for (AttachmentDto att : attachments) {
            Path file = Paths.get(uploadDir, String.valueOf(userId), att.getKey());
            String filename = att.getFilename() != null ? att.getFilename() : "unknown";
            String contentType = att.getContentType() != null ? att.getContentType() : "application/octet-stream";
            long size = att.getSize() != null ? att.getSize() : 0;

            try {
                if (contentType.startsWith("image/")) {
                    // 图片：只记日志，文本里不嵌入（hasImage=true 时会走原生 API content 数组）
                    byte[] data = Files.readAllBytes(file);
                    log.info("[Attach] image registered for native API: {} ({} bytes, {})", filename, data.length, contentType);
                } else if (isTextFile(filename, contentType)) {
                    byte[] data = Files.readAllBytes(file);
                    String content = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                    if (content.length() > 20000) content = content.substring(0, 20000) + "\n...(截断)";
                    textFileBuffer.append("\n\n--- 附件 ").append(filename).append(" ---\n");
                    textFileBuffer.append(content).append("\n--- 附件结束 ---\n");
                    log.info("[Attach] loaded text file {} ({} bytes)", filename, data.length);
                } else {
                    otherBuffer.append("\n\n[附件] ").append(filename).append(" (")
                            .append(humanSize(size)).append(", ").append(contentType).append(") — 二进制文件，无法直接解析。");
                }
            } catch (Exception e) {
                log.warn("[Attach] failed to load {}: {}", filename, e.getMessage());
                otherBuffer.append("\n\n[附件] ").append(filename).append(" — 加载失败: ").append(e.getMessage());
            }
        }

        // 组装最终文本（图片不写进这里，走原生 API 的 content 数组）
        StringBuilder finalText = new StringBuilder();
        if (textFileBuffer.length() > 0 || otherBuffer.length() > 0) {
            finalText.append("以下是用户附带的文件：\n");
            finalText.append(textFileBuffer).append(otherBuffer);
            finalText.append("\n\n--- 用户问题 ---\n");
        }
        finalText.append(userText);

        messages.set(lastUserIdx, new UserMessage(finalText.toString()));
        log.info("[Attach] text prompt length={}", finalText.length());
    }

    /**
     * 对模型返回的挤成一坨的内容做 markdown 格式化补换行。
     * 处理 deepseek-flash vision 模型经常忽略换行符的情况。
     */
    private static String fixMarkdownFormatting(String raw) {
        if (raw == null || raw.isBlank()) return raw == null ? "" : raw;

        String s = raw;

        // 1. 标题标记前补换行（##/###/####/#####/######）
        // 匹配非开头位置的 ## 且前面不是换行
        s = s.replaceAll("(?<!\\n)(?<=.)##+", "\n##");

        // 2. 标题标记后确保有空格（##标题 → ## 标题）
        s = s.replaceAll("(#{2,6})(?=[^#\\s])", "$1 ");

        // 3. 列表项 - 和 * 前补换行（不在行首且前面不是换行）
        s = s.replaceAll("(?<!\\n)(?<=.)(?:[-*+])(?=\\s)", "\n$0");

        // 4. 有序列表 1. 2. 前补换行
        s = s.replaceAll("(?<!\\n)(?<=.)(\\d+\\.)(?=\\s)", "\n$1");

        // 5. > 引用前补换行
        s = s.replaceAll("(?<!\\n)(?<=.)>(?=\\s)", "\n> ");

        // 6. --- / *** 分割线前后补空行
        s = s.replaceAll("(?<!\\n)(?<=.)(---+|\\*\\*\\*+|___+)", "\n\n$1\n\n");

        // 7. 反引号 ``` 前后补换行（代码块）
        s = s.replaceAll("(?<!\\n)(?<=.)```", "\n```\n");
        s = s.replaceAll("```(?!\\n)", "```\n");

        // 8. 中文句号、问号、感叹号后跟大写/数字开头的新段落 → 空行
        //    例如 "xxx##一、浏览器环境" → 已在第1步处理
        s = s.replaceAll("([。！？])([^\\n\\s])", "$1\n");

        // 9. 多个连续空行合并成两个
        s = s.replaceAll("\n{3,}", "\n\n");

        // 10. 清理开头结尾的多余换行
        s = s.replaceAll("^\\n+", "");
        s = s.replaceAll("\\n+$", "");

        return s;
    }

    private static boolean isTextFile(String filename, String contentType) {
        if (contentType.startsWith("text/")) return true;
        if (contentType.contains("json") || contentType.contains("xml") || contentType.contains("yaml") || contentType.contains("csv")) return true;
        String lower = filename.toLowerCase();
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json")
                || lower.endsWith(".xml") || lower.endsWith(".yml") || lower.endsWith(".yaml")
                || lower.endsWith(".csv") || lower.endsWith(".log") || lower.endsWith(".env")
                || lower.endsWith(".py") || lower.endsWith(".js") || lower.endsWith(".ts")
                || lower.endsWith(".jsx") || lower.endsWith(".tsx") || lower.endsWith(".java")
                || lower.endsWith(".go") || lower.endsWith(".rs") || lower.endsWith(".c")
                || lower.endsWith(".cpp") || lower.endsWith(".cs") || lower.endsWith(".rb")
                || lower.endsWith(".php") || lower.endsWith(".html") || lower.endsWith(".css")
                || lower.endsWith(".sql") || lower.endsWith(".sh") || lower.endsWith(".bat");
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /**
     * 发送 SSE compress_start / compress_end 事件通知前端。
     */
    private List<Message> compressIfNeeded(List<Message> messages, Sinks.Many<ServerSentEvent<String>> sink) {
        int tokenEst = estimateTokens(messages);
        if (tokenEst <= CONTEXT_TOKEN_THRESHOLD) return messages;

        log.info("Context too large (≈{} tokens), compressing...", tokenEst);

        // 通知前端：压缩开始
        emit(sink, ServerSentEvent.<String>builder()
                .event("compress_start")
                .data("{\"beforeTokens\":" + tokenEst + "}")
                .build());

        try {
            // 取需要压缩的消息（保留最近 KEEP_RECENT_MESSAGES 条原文）
            int totalMsgs = messages.size(); // 含 SystemMessage
            int compressEnd = totalMsgs - KEEP_RECENT_MESSAGES;
            if (compressEnd <= 1) return messages; // 没什么可压缩的

            List<Message> toCompress = messages.subList(1, compressEnd); // 跳过 SystemMessage
            List<Message> toKeep = messages.subList(compressEnd, totalMsgs);

            // 构建压缩请求 prompt
            StringBuilder dialog = new StringBuilder();
            for (Message m : toCompress) {
                String role = (m instanceof UserMessage) ? "用户" : (m instanceof AssistantMessage) ? "助手" : "系统";
                dialog.append(role).append(": ").append(m.getText()).append("\n");
            }

            String compressPrompt = "请将以下对话历史压缩成一段简洁的摘要，保留关键信息、决策和上下文，不超过 500 字：\n\n" + dialog;

            // 用非流式调用生成摘要
            List<Message> compressMessages = new ArrayList<>();
            compressMessages.add(new SystemMessage("你是一个对话摘要助手，负责将冗长的对话历史压缩成简洁的摘要。"));
            compressMessages.add(new UserMessage(compressPrompt));

            ChatResponse summaryResp = chatModel.call(new Prompt(compressMessages));
            String summary = summaryResp.getResult().getOutput().getText();

            if (summary == null || summary.isBlank()) {
                log.warn("Compression produced empty summary, skipping");
                return messages;
            }

            // 组装压缩后的消息列表
            List<Message> compressed = new ArrayList<>();
            compressed.add(messages.get(0)); // SystemMessage
            compressed.add(new SystemMessage("## 对话历史摘要\n" + summary));
            compressed.addAll(toKeep);

            int afterTokens = estimateTokens(compressed);
            log.info("Context compressed: {} → {} messages, ≈{} → ≈{} tokens",
                    totalMsgs, compressed.size(), tokenEst, afterTokens);

            // 通知前端：压缩完成
            emit(sink, ServerSentEvent.<String>builder()
                    .event("compress_end")
                    .data("{\"afterTokens\":" + afterTokens + ",\"summaryLength\":" + summary.length() + "}")
                    .build());

            return compressed;
        } catch (Exception e) {
            log.error("Context compression failed", e);
            emit(sink, ServerSentEvent.<String>builder()
                    .event("compress_end")
                    .data("{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}")
                    .build());
            return messages; // 压缩失败就用原消息继续
        }
    }

    /**
     * 运行 Agent 循环，返回 SSE 流。最后一个 done 事件包含 finalContent（完整 assistant 回复）。
     */
    public Flux<ServerSentEvent<String>> run(Session session, ChatMessageRequest request) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer(512);

        // 注入 AskUserQuestion 的 SSE 发送回调
        askUserQuestionTool.setSseEmitter((eventName, payload) -> {
            try {
                String json = objectMapper.writeValueAsString(payload);
                sink.tryEmitNext(ServerSentEvent.<String>builder()
                        .event(eventName)
                        .data(json)
                        .build());
            } catch (Exception e) {
                log.error("Failed to emit {} event", eventName, e);
            }
        });

        long totalStart = System.currentTimeMillis();

        List<ToolCallback> toolCallbacks = toolRegistry.toToolCallbacks();

        // 组装消息：SystemPrompt + DB 历史对话（含当前用户最新一条，ChatController 已预先写入）
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(getSystemPrompt()));

        List<com.lumenai.backend.entity.Message> history = messageMapper.selectList(
                new LambdaQueryWrapper<com.lumenai.backend.entity.Message>()
                        .eq(com.lumenai.backend.entity.Message::getSessionId, session.getId())
                        .eq(com.lumenai.backend.entity.Message::getUserId, session.getUserId())
                        .orderByAsc(com.lumenai.backend.entity.Message::getId)
                        .last("LIMIT 50")
        );
        for (com.lumenai.backend.entity.Message m : history) {
            if (m.getContent() == null || m.getContent().isBlank()) continue;
            switch (m.getRole() == null ? "" : m.getRole().toLowerCase()) {
                case "user" -> messages.add(new UserMessage(m.getContent()));
                case "assistant" -> messages.add(new AssistantMessage(m.getContent()));
                default -> log.debug("Skipping history message role={}, id={}", m.getRole(), m.getId());
            }
        }

        // 处理附件：替换最后一条 UserMessage
        if (request.getAttachments() != null && !request.getAttachments().isEmpty()) {
            applyAttachments(messages, request.getAttachments(), session.getUserId());
        }

        log.info("Agent context loaded: {} messages for session {}", messages.size() - 1, session.getId());

        // 上下文过长时自动压缩（发送 SSE compress_start/compress_end）
        final List<Message> finalMessages = compressIfNeeded(messages, sink);

        // 判断是否有图片附件 — 有则切 flash 模型且不开 thinking
        boolean hasImage = request.getAttachments() != null
                && request.getAttachments().stream().anyMatch(AttachmentDto::isImage);
        String runtimeModel = configuredModel;
        boolean enableThinking = !hasImage;
        if (hasImage) runtimeModel = "deepseek-flash";

        DeepSeekChatOptions options = enableThinking
                ? DeepSeekChatOptions.builder()
                        .toolCallbacks(toolCallbacks)
                        .model(runtimeModel)
                        .enableThinking()
                        .reasoningEffortHigh()
                        .build()
                : DeepSeekChatOptions.builder()
                        .toolCallbacks(toolCallbacks)
                        .model(runtimeModel)
                        .build();

        log.info("[Agent] model={}, enableThinking={}, hasImage={}", runtimeModel, enableThinking, hasImage);

        // ====== 有图片 → 绕过 Spring AI ChatModel，直接调 deepseek-flash 原生多模态 API ======
        if (hasImage) {
            log.info("[hasImage=true] early return → streamDeepseekFlashNative(), deepseekApiKey present={}",
                    deepseekApiKey != null && !deepseekApiKey.isBlank());
            Flux<ServerSentEvent<String>> nativeFlux = streamDeepseekFlashNative(finalMessages, request, session);
            log.info("[hasImage=true] returned from streamDeepseekFlashNative, flux={}", nativeFlux != null);
            return nativeFlux;
        }

        // 累计元数据
        StringBuilder finalContent = new StringBuilder();
        Metadata meta = new Metadata();

        new Thread(() -> {
            // 把 userId 传递到 agent 线程（ThreadLocal 不跨线程，需手动设置）
            UserContext.set(session.getUserId());
            try {
                runLoop(finalMessages, options, sink, cancelled, finalContent, meta, session.getUserId());
            } catch (Exception e) {
                log.error("Agent loop error", e);
                emit(sink, ServerSentEvent.<String>builder()
                        .event("error")
                        .data("{\"code\":\"INTERNAL_ERROR\",\"message\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}")
                        .build());
            } finally {
                UserContext.clear();
                meta.latencyMs = System.currentTimeMillis() - totalStart;
                log.info("[DEBUG] Agent finally block, cancelled={}, finalContentLen={}, thread={}", cancelled.get(), finalContent.length(), Thread.currentThread().getName());
                try {
                    // 用配置兜底 model（Spring AI 2.0 DeepSeek 流式不返回 model/usage metadata）
                    String model = (meta.model != null && !meta.model.isBlank()) ? meta.model : configuredModel;

                    Map<String, Object> usage = new LinkedHashMap<>();
                    usage.put("input_tokens", meta.inputTokens);
                    usage.put("output_tokens", meta.outputTokens);

                    Map<String, Object> donePayload = new LinkedHashMap<>();
                    donePayload.put("usage", usage);
                    donePayload.put("model", model);
                    donePayload.put("toolCalls", meta.toolCalls);
                    donePayload.put("latencyMs", meta.latencyMs);
                    donePayload.put("finalContent", finalContent.toString());
                    donePayload.put("cancelled", cancelled.get());
                    emit(sink, ServerSentEvent.<String>builder()
                            .event("done")
                            .data(objectMapper.writeValueAsString(donePayload))
                            .build());
                } catch (Exception e) {
                    log.warn("Failed to emit done event", e);
                }
                sink.tryEmitComplete();

                // 客户端已断开（cancelled=true）时，主动存库 assistant message
                // 正常完成时由 ChatController.doOnNext 存库，避免重复
                if (cancelled.get() && finalContent.length() > 0) {
                    try {
                        String model = (meta.model != null && !meta.model.isBlank()) ? meta.model : configuredModel;
                        com.lumenai.backend.entity.Message msg = new com.lumenai.backend.entity.Message();
                        msg.setSessionId(session.getId());
                        msg.setUserId(session.getUserId());
                        msg.setRole("assistant");
                        msg.setContent(finalContent.toString());
                        msg.setModel(model);
                        msg.setLatencyMs((int) meta.latencyMs);
                        msg.setInputTokens(meta.inputTokens);
                        msg.setOutputTokens(meta.outputTokens);
                        msg.setCancelled(true);
                        msg.setCreatedAt(java.time.LocalDateTime.now());
                        if (meta.toolCalls != null) {
                            msg.setToolCalls(objectMapper.writeValueAsString(meta.toolCalls));
                        }
                        messageMapper.insert(msg);
                        log.info("Saved assistant message (cancelled), sessionId={}, len={}",
                                session.getId(), finalContent.length());
                    } catch (Exception e) {
                        log.warn("Failed to save cancelled assistant message", e);
                    }
                }
            }
        }, "agent-" + session.getId()).start();

        return sink.asFlux()
                .takeUntilOther(Flux.never())
                .doOnCancel(() -> { log.info("[DEBUG] Flux cancelled! Setting cancelled=true, finalContentLen={}", finalContent.length()); cancelled.set(true); });
    }

    private void emit(Sinks.Many<ServerSentEvent<String>> sink, ServerSentEvent<String> event) {
        try {
            Sinks.EmitResult result = sink.tryEmitNext(event);
            if (result.isFailure()) log.warn("Sink emit failed: {}", result);
        } catch (Exception e) {
            log.warn("Sink emit exception: {}", e.getMessage());
        }
    }

    // ==================== 权限校验 ====================

    /** 从 DB 读取用户的 permissionMode */
    private String getPermissionMode(Long userId) {
        try {
            User user = userMapper.selectById(userId);
            if (user != null && user.getPermissionMode() != null) {
                return user.getPermissionMode();
            }
        } catch (Exception e) {
            log.warn("Failed to read permissionMode for userId={}", userId, e);
        }
        return "smart"; // 兜底
    }

    /** 从 DB permissions 表读取工具的 default_mode，缓存避免每次查库 */
    private volatile Map<String, String> toolPermissionCache;

    private String getToolDefaultMode(String toolName) {
        if (toolPermissionCache == null) {
            synchronized (this) {
                if (toolPermissionCache == null) {
                    Map<String, String> map = new HashMap<>();
                    try {
                        List<Permission> list = permissionMapper.selectList(null);
                        if (list != null) {
                            for (Permission p : list) {
                                map.put(p.getToolName(), p.getDefaultMode());
                            }
                        }
                        log.info("[Permission] loaded tool permissions from DB: {}", map);
                    } catch (Exception e) {
                        log.warn("[Permission] failed to load permissions table, using defaults: {}", e.getMessage());
                    }
                    toolPermissionCache = map;
                }
            }
        }
        return toolPermissionCache.getOrDefault(toolName, "ask_user"); // 未知工具默认需要审批
    }

    /**
     * 校验工具调用权限。返回 Map<toolCallId, approvedBy>，null 表示拒绝。
     * approvedBy 值: "auto"（AI 自动批准） / "user"（用户手动批准）
     * 权限校验逻辑（对齐 Trae 定义）：
     * - fully: 直接在用户电脑上运行，不隔离，所有工具自动放行（包括 deny）
     * - manual: 隔离环境，高风险（deny）阻止，重要操作（ask_user）需用户批准，安全操作（auto_allow）放行
     * - smart: 隔离环境，高风险（deny）阻止，重要操作（ask_user）AI 自动批准，安全操作（auto_allow）放行
     */
    private Map<String, String> checkToolPermissions(Long userId, AssistantMessage assistantMsg,
                                          String permissionMode, Sinks.Many<ServerSentEvent<String>> sink) {
        var toolCalls = assistantMsg.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) return Collections.emptyMap();

        Map<String, String> approvalMap = new LinkedHashMap<>();

        for (var tc : toolCalls) {
            String toolName = tc.name();
            String toolMode = getToolDefaultMode(toolName);

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 完全访问：直接在用户电脑上运行，不隔离，不审批
            // 所有工具自动放行（包括 deny 也忽略）
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            if ("fully".equals(permissionMode)) {
                log.info("[Permission] fully mode, auto-allow tool={}", toolName);
                approvalMap.put(tc.id(), "auto");
                continue;
            }

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 手动审批 & 自动审批：都在隔离环境中运行
            // 高风险操作（deny）直接阻止
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            if ("deny".equals(toolMode)) {
                log.info("[Permission] high-risk tool {} blocked in {} mode", toolName, permissionMode);
                // 发 SSE 通知前端工具被阻止
                try {
                    Map<String, Object> blockedInfo = new LinkedHashMap<>();
                    blockedInfo.put("toolName", toolName);
                    blockedInfo.put("reason", "high_risk_blocked");
                    blockedInfo.put("mode", permissionMode);
                    emit(sink, ServerSentEvent.<String>builder()
                            .event("tool_blocked")
                            .data(objectMapper.writeValueAsString(blockedInfo))
                            .build());
                } catch (Exception e) {
                    log.warn("[Permission] failed to emit tool_blocked", e);
                }
                return null;
            }

            // 安全操作（auto_allow）：手动审批和自动审批都放行
            if ("auto_allow".equals(toolMode)) {
                log.info("[Permission] safe tool {} auto-allowed in {} mode", toolName, permissionMode);
                approvalMap.put(tc.id(), "auto");
                continue;
            }

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 重要操作（ask_user）：
            //   手动审批 → 需要用户批准（弹框）
            //   自动审批 → AI 自动批准（不弹框）
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            if ("smart".equals(permissionMode)) {
                // 自动审批：AI 自动批准重要操作
                log.info("[Permission] smart mode, auto-approve important tool={}", toolName);
                approvalMap.put(tc.id(), "auto");
                continue;
            }

            // 手动审批：发 permission_request 等用户确认
            String requestId = UUID.randomUUID().toString();
            PermissionPending pending = new PermissionPending();
            pendingPermissions.put(requestId, pending);

            try {
                Map<String, Object> permReq = new LinkedHashMap<>();
                permReq.put("requestId", requestId);
                permReq.put("toolName", toolName);
                permReq.put("args", normalizeArgs(tc.arguments()));
                permReq.put("userId", userId);
                permReq.put("mode", permissionMode);
                emit(sink, ServerSentEvent.<String>builder()
                        .event("permission_request")
                        .data(objectMapper.writeValueAsString(permReq))
                        .build());
                log.info("[Permission] sent permission_request, tool={}, requestId={}", toolName, requestId);
            } catch (Exception e) {
                log.warn("[Permission] failed to emit permission_request", e);
                pendingPermissions.remove(requestId);
                return null;
            }

            // 阻塞等待前端响应，最多 5 分钟
            try {
                if (!pending.latch.await(5, TimeUnit.MINUTES)) {
                    log.warn("[Permission] timeout waiting for user response, requestId={}", requestId);
                    pendingPermissions.remove(requestId);
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pendingPermissions.remove(requestId);
                return null;
            }

            pendingPermissions.remove(requestId);
            if (!pending.approved) {
                log.info("[Permission] user denied tool={}, requestId={}", toolName, requestId);
                return null;
            }
            log.info("[Permission] user approved tool={}, requestId={}", toolName, requestId);
            approvalMap.put(tc.id(), "user");
        }

        return approvalMap;
    }

    /** 前端确认权限后调用此方法解锁等待的线程 */
    public void resolvePermission(String requestId, boolean approved) {
        PermissionPending pending = pendingPermissions.get(requestId);
        if (pending == null) {
            log.warn("[Permission] no pending request found for requestId={}", requestId);
            return;
        }
        pending.approved = approved;
        pending.latch.countDown();
        log.info("[Permission] resolved requestId={}, approved={}", requestId, approved);
    }

    /** 前端回答 AskUserQuestion 后调用此方法解锁等待线程 */
    public void resolveQuestion(String requestId, String answer) {
        askUserQuestionTool.resolveQuestion(requestId, answer);
    }

    private void runLoop(List<Message> messages,
                         DeepSeekChatOptions options,
                         Sinks.Many<ServerSentEvent<String>> sink,
                         AtomicBoolean cancelled,
                         StringBuilder finalContent,
                         Metadata meta,
                         Long userId) {

        Prompt prompt = new Prompt(messages, options);

        for (int round = 0; round < lumenProperties.getAi().getAgent().getMaxLoopRounds(); round++) {
            if (cancelled.get()) {
                log.info("Agent loop cancelled at round {}, finalContentLen={}", round, finalContent.length());
                return;
            }

            // 累积本轮的 reasoning_content（DeepSeek 思考模式要求工具调用时回传）
            StringBuilder roundReasoning = new StringBuilder();

            // 1. 流式调用 LLM，逐个 chunk 发 SSE text 事件，同时收集所有 chunks
            Flux<ChatResponse> stream = chatModel.stream(prompt);

            List<ChatResponse> chunks;
            try {
                chunks = stream
                    .takeUntil(item -> cancelled.get())
                    .doOnNext(chunk -> {
                        // 从 chunk metadata 里尝试提取 tokens 和 model（遍历 Map，兼容 Spring AI 2.0 的各种 provider 实现）
                        try {
                            var crMeta = chunk.getMetadata();  // ChatResponseMetadata extends Map
                            for (Map.Entry<String, Object> e : crMeta.entrySet()) {
                                String k = e.getKey().toLowerCase();
                                Object v = e.getValue();
                                // 模型名
                                if ((k.equals("model") || k.equals("Model")) && v instanceof String s && !s.isBlank()) {
                                    meta.model = s;
                                }
                                // usage（可能是嵌套 Map）
                                if ((k.equals("usage") || k.equals("Usage")) && v instanceof Map<?, ?> um) {
                                    Object in = um.get("input_tokens");
                                    if (in == null) in = um.get("prompt_tokens");
                                    Object out = um.get("output_tokens");
                                    if (out == null) out = um.get("completion_tokens");
                                    if (in instanceof Number n) meta.inputTokens += n.intValue();
                                    if (out instanceof Number n) meta.outputTokens += n.intValue();
                                }
                            }
                        } catch (Exception ignored) {}

                        // DEBUG: 打印 chunk 结构，找 reasoning_content
                        if (log.isDebugEnabled()) {
                            AssistantMessage am = chunk.getResult() != null ? chunk.getResult().getOutput() : null;
                            if (am != null) {
                                log.debug("CHUNK DEBUG: text='{}', metadata={}", am.getText(), am.getMetadata());
                            }
                        }

                        // 提取 reasoning_content（如果有）
                        String reasoningDelta = extractReasoningDelta(chunk);
                        if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
                            roundReasoning.append(reasoningDelta);
                            try {
                                String json = "{\"delta\":" + objectMapper.writeValueAsString(reasoningDelta) + "}";
                                emit(sink, ServerSentEvent.<String>builder().event("reasoning").data(json).build());
                            } catch (Exception e) {
                                log.warn("Failed to emit reasoning chunk", e);
                            }
                        }

                        String text = extractDeltaText(chunk);
                        if (text != null && !text.isEmpty()) {
                            finalContent.append(text);
                            try {
                                String json = "{\"delta\":" + objectMapper.writeValueAsString(text) + "}";
                                emit(sink, ServerSentEvent.<String>builder().event("text").data(json).build());
                            } catch (Exception e) {
                                log.warn("Failed to emit text chunk", e);
                            }
                        }
                    })
                    .collectList()
                    .block();
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                // 尝试提取 DeepSeek 返回的具体错误信息
                if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException wce) {
                    String body = wce.getResponseBodyAsString();
                    log.error("DeepSeek API error: {} | Status: {} | Body: {}", 
                            wce.getMessage(), wce.getStatusCode(), body);
                    errorMsg = body != null && !body.isBlank() ? body : wce.getMessage();
                } else {
                    log.error("Agent loop error", e);
                }
                // 发送错误事件给前端
                emit(sink, ServerSentEvent.<String>builder()
                        .event("error")
                        .data("{\"error\":\"" + errorMsg.replace("\"", "\\\"") + "\"}")
                        .build());
                return;
            }

            if (chunks == null || chunks.isEmpty()) {
                log.warn("LLM returned empty response at round {}", round);
                break;
            }

            // 取最后一个 chunk 作为完整响应
            ChatResponse response = chunks.get(chunks.size() - 1);

            // 2. 终止条件：没有 tool calls
            if (!response.hasToolCalls()) {
                log.debug("Agent loop finished at round {} (no tool calls)", round);
                break;
            }

            // 3. 发 tool_call_start 事件 + 收集 tool_calls JSON
            AssistantMessage assistantMsg = Objects.requireNonNull(response.getResult()).getOutput();
            // 3.5. 权限校验：根据用户的 permissionMode 决定是否需要审批
            Map<String, String> approvalMap = null;
            if (assistantMsg.hasToolCalls()) {
                String permissionMode = getPermissionMode(userId);
                approvalMap = checkToolPermissions(userId, assistantMsg, permissionMode, sink);
                if (approvalMap == null) {
                    // 用户拒绝了工具执行，跳出循环
                    log.info("Tool execution denied by user, userId={}", userId);
                    break;
                }
            }
            if (assistantMsg.hasToolCalls()) {
                for (var tc : assistantMsg.getToolCalls()) {
                    try {
                        Map<String, Object> tcInfo = new LinkedHashMap<>();
                        tcInfo.put("id", tc.id());
                        tcInfo.put("name", tc.name());
                        tcInfo.put("status", "running");
                        tcInfo.put("args", normalizeArgs(tc.arguments()));
                        tcInfo.put("createdAt", System.currentTimeMillis());
                        // 标注审批方式：auto（AI 自动批准） / user（用户手动批准）
                        String approvedBy = approvalMap != null ? approvalMap.get(tc.id()) : null;
                        if (approvedBy != null) {
                            tcInfo.put("approvedBy", approvedBy);
                        }
                        // 收集到 meta 里，存 DB 用（含 approvedBy）
                        Map<String, Object> tcMeta = new LinkedHashMap<>();
                        tcMeta.put("id", tc.id());
                        tcMeta.put("name", tc.name());
                        tcMeta.put("args", normalizeArgs(tc.arguments()));
                        if (approvedBy != null) {
                            tcMeta.put("approvedBy", approvedBy);
                        }
                        meta.toolCalls.add(tcMeta);
                        emit(sink, ServerSentEvent.<String>builder()
                                .event("tool_call_start")
                                .data(objectMapper.writeValueAsString(tcInfo))
                                .build());
                    } catch (Exception e) {
                        log.warn("Failed to emit tool_call_start", e);
                    }
                }
            }

            // 4. ToolCallingManager 执行所有工具
            long toolStart = System.currentTimeMillis();
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            int totalDur = (int) (System.currentTimeMillis() - toolStart);

            // 5. 发 tool_call_end 事件 + 回写 meta.toolCalls（补 status/output/durationMs）
            for (Message msg : result.conversationHistory()) {
                if (msg instanceof ToolResponseMessage trm) {
                    for (var tr : trm.getResponses()) {
                        try {
                            String outputStr;
                            Object rawOutput = tr.responseData();
                            if (rawOutput == null) {
                                outputStr = "";
                            } else if (rawOutput instanceof String s) {
                                outputStr = s;
                            } else {
                                outputStr = objectMapper.writeValueAsString(rawOutput);
                            }
                            Map<String, Object> evt = new LinkedHashMap<>();
                            evt.put("id", tr.id());
                            evt.put("name", tr.name());
                            evt.put("status", "success");
                            evt.put("output", outputStr);
                            evt.put("durationMs", totalDur);
                            evt.put("createdAt", System.currentTimeMillis());
                            // 补充审批方式：tool_call_end 也带上 approvedBy，避免前端覆盖丢失
                            String endApprovedBy = approvalMap != null ? approvalMap.get(tr.id()) : null;
                            if (endApprovedBy != null) {
                                evt.put("approvedBy", endApprovedBy);
                            }
                            emit(sink, ServerSentEvent.<String>builder()
                                    .event("tool_call_end")
                                    .data(objectMapper.writeValueAsString(evt))
                                    .build());

                            // 回写到 meta.toolCalls，存 DB 用
                            for (int i = 0; i < meta.toolCalls.size(); i++) {
                                Map<String, Object> stored = meta.toolCalls.get(i);
                                if (tr.id().equals(stored.get("id"))) {
                                    Map<String, Object> updated = new LinkedHashMap<>(stored);
                                    updated.put("status", "success");
                                    updated.put("output", outputStr);
                                    updated.put("durationMs", totalDur);
                                    meta.toolCalls.set(i, updated);
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Failed to emit tool_call_end", e);
                        }
                    }
                }
            }

            // 6. 更新 prompt — 将本轮 reasoning_content 注入到 AssistantMessage
            // DeepSeek 思考模式要求工具调用轮次回传 reasoning_content，否则返回 400
            if (!roundReasoning.isEmpty()) {
                List<Message> patchedHistory = new ArrayList<>();
                for (Message m : result.conversationHistory()) {
                    if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                        // 用 DeepSeekAssistantMessage.builder() 重建，携带 reasoning_content
                        patchedHistory.add(org.springframework.ai.deepseek.DeepSeekAssistantMessage.builder()
                                .content(am.getText() != null ? am.getText() : "")
                                .reasoningContent(roundReasoning.toString())
                                .toolCalls(am.getToolCalls())
                                .build());
                    } else {
                        patchedHistory.add(m);
                    }
                }
                prompt = new Prompt(patchedHistory, options);
            } else {
                prompt = new Prompt(result.conversationHistory(), options);
            }
        }

        log.info("Agent loop completed (max rounds: {})", lumenProperties.getAi().getAgent().getMaxLoopRounds());

        // 兜底：如果流式没返回 usage，用文本长度估算 token
        if (meta.inputTokens == 0) {
            // 输入 token = 所有消息文本长度 / 4（粗略估算）
            int inputChars = 0;
            for (Message m : messages) {
                if (m.getText() != null) inputChars += m.getText().length();
            }
            meta.inputTokens = inputChars / 4;
        }
        if (meta.outputTokens == 0) {
            // 输出 token = 最终回复文本长度 / 4
            meta.outputTokens = finalContent.length() / 4;
        }
    }

    private String extractDeltaText(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null) return null;
        AssistantMessage msg = chunk.getResult().getOutput();
        if (msg.getText() != null && !msg.getText().isEmpty()) {
            return msg.getText();
        }
        return null;
    }

    /**
     * 从 chunk 中提取 reasoning_content（深度思考过程）。
     * 优先使用 DeepSeekAssistantMessage.getReasoningContent()，
     * 兼容 AssistantMessage metadata 里包含 "reasoning" 或 "thinking" 的值。
     */
    private String extractReasoningDelta(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null) return null;
        AssistantMessage msg = chunk.getResult().getOutput();

        // 优先：DeepSeekAssistantMessage 有专门的 reasoningContent 字段
        if (msg instanceof org.springframework.ai.deepseek.DeepSeekAssistantMessage dmsg) {
            String rc = dmsg.getReasoningContent();
            if (rc != null && !rc.isEmpty()) return rc;
        }

        // 兼容：遍历 metadata
        Map<String, Object> meta = msg.getMetadata();
        if (meta != null) {
            for (Map.Entry<String, Object> e : meta.entrySet()) {
                String k = e.getKey().toLowerCase();
                Object v = e.getValue();
                if ((k.contains("reasoning") || k.contains("thinking")) && v instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        }

        return null;
    }

    /**
     * Spring AI ToolCall.arguments() 可能返回 JSON 字符串或已解析的 Map，
     * 这里统一解析成 Object，确保前端收到的始终是 JSON 对象。
     */
    private Object normalizeArgs(Object raw) {
        if (raw == null) return Map.of();
        if (raw instanceof Map) return raw;
        if (raw instanceof String s) {
            if (s.isBlank()) return Map.of();
            try {
                return objectMapper.readValue(s, Object.class);
            } catch (Exception e) {
                return Map.of("raw", s);
            }
        }
        return raw;
    }

    /**
     * 原生 HttpURLConnection 调 deepseek-flash chat/completions 多模态 API（streaming）。
     * 构造 GPT-4o 风格的 content 数组，图片用 data:image/xxx;base64,url 格式。
     * 用 BufferedReader 逐行读 SSE，按 data: {...} 解析后 emit 到 sink。
     */
    private Flux<ServerSentEvent<String>> streamDeepseekFlashNative(
            List<Message> messages, ChatMessageRequest request, Session session) {

        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer(512);
        log.info("[NativeVision] ENTER streamDeepseekFlashNative, attachments={}",
                request.getAttachments() == null ? 0 : request.getAttachments().size());

        new Thread(() -> {
            log.info("[NativeVision] Thread started, deepseekApiKey length={}, baseUrl={}",
                    deepseekApiKey == null ? 0 : deepseekApiKey.length(), deepseekBaseUrl);
            try {
                // === 1. 构造 messages JSON（历史部分纯文本） ===
                List<Map<String, Object>> apiMessages = new ArrayList<>();
                for (Message m : messages) {
                    String role = m instanceof SystemMessage ? "system"
                               : m instanceof UserMessage ? "user"
                               : m instanceof AssistantMessage ? "assistant"
                               : "user";
                    String text = m.getText() == null ? "" : m.getText();
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("role", role);
                    msg.put("content", text);
                    apiMessages.add(msg);
                }

                // 给 system 消息追加格式强化指令（deepseek-flash vision 模型默认容易忽略换行）
                for (Map<String, Object> msg : apiMessages) {
                    if ("system".equals(msg.get("role"))) {
                        String sysText = (String) msg.get("content");
                        sysText = sysText + "\n\n【输出格式强制要求】\n" +
                                "你必须使用标准 Markdown 格式输出回答，严格遵守以下规则：\n" +
                                "1. 每个段落、标题、列表项之间必须用换行符分隔\n" +
                                "2. 标题格式：## 一级标题（注意 ## 和文字之间必须有空格）\n" +
                                "3. 列表格式：\n- 每个列表项单独一行\n- 用 - 或 * 开头，后面跟空格\n" +
                                "4. 段落之间空一行\n" +
                                "5. 加粗：**文字** 斜体：*文字* 代码：`code`\n" +
                                "6. 禁止把所有内容挤成一段，禁止缺少换行符\n";
                        msg.put("content", sysText);
                        break;
                    }
                }

                // === 2. 最后一条 user：换成 content 数组（image_url + text） ===
                int lastUserIdx = -1;
                for (int i = apiMessages.size() - 1; i >= 0; i--) {
                    if ("user".equals(apiMessages.get(i).get("role"))) { lastUserIdx = i; break; }
                }
                if (lastUserIdx >= 0) {
                    List<Map<String, Object>> contentArr = new ArrayList<>();
                    // 图片 parts
                    for (AttachmentDto att : request.getAttachments()) {
                        if (!att.isImage()) continue;
                        Path file = Paths.get(uploadDir, String.valueOf(session.getUserId()), att.getKey());
                        byte[] data = Files.readAllBytes(file);
                        String b64 = java.util.Base64.getEncoder().encodeToString(data);
                        String mime = att.getContentType() != null ? att.getContentType() : "image/png";
                        Map<String, Object> imgPart = new HashMap<>();
                        imgPart.put("type", "image_url");
                        Map<String, String> imgUrl = new HashMap<>();
                        imgUrl.put("url", "data:" + mime + ";base64," + b64);
                        imgPart.put("image_url", imgUrl);
                        contentArr.add(imgPart);
                    }
                    // text part
                    String userText = (String) apiMessages.get(lastUserIdx).get("content");
                    Map<String, Object> textPart = new HashMap<>();
                    textPart.put("type", "text");
                    textPart.put("text", userText == null ? "" : userText);
                    contentArr.add(textPart);
                    apiMessages.get(lastUserIdx).put("content", contentArr);
                }

                // === 3. 构造 body ===
                Map<String, Object> body = new HashMap<>();
                body.put("model", "deepseek-flash");
                body.put("stream", true);
                body.put("messages", apiMessages);
                String bodyJson = objectMapper.writeValueAsString(body);
                log.info("[NativeVision] body size={} bytes, lastUser content is array={}",
                        bodyJson.length(), apiMessages.get(lastUserIdx).get("content") instanceof List);

                // === 4. HttpURLConnection 发请求 ===
                String urlStr = deepseekBaseUrl.replaceAll("/$", "") + "/chat/completions";
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + deepseekApiKey);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(120000);

                try (var os = conn.getOutputStream()) {
                    os.write(bodyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                log.info("[NativeVision] HTTP {}", code);
                if (code != 200) {
                    String err = new String(conn.getErrorStream().readAllBytes());
                    log.error("[NativeVision] error body: {}", err);
                    sink.tryEmitNext(ServerSentEvent.<String>builder().event("error").data("{\"message\":\"HTTP " + code + ": " + err.replace("\"", "'") + "\"}").build());
                    sink.tryEmitComplete();
                    return;
                }

                // === 5. 读 SSE 流 ===
                long startMs = System.currentTimeMillis();
                StringBuilder finalContent = new StringBuilder();
                int inputTokens = 0, outputTokens = 0;

                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    StringBuilder sseEvent = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            // 空行 = 一个 SSE event 结束
                            String dataStr = sseEvent.toString().trim();
                            if (!dataStr.isEmpty() && dataStr.startsWith("data:")) {
                                String json = dataStr.substring(5).trim();
                                if ("[DONE]".equals(json)) break;
                                try {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> ev = objectMapper.readValue(json, Map.class);
                                    // 提取 content
                                    List<Map<String, Object>> choices = (List) ev.get("choices");
                                    if (choices != null && !choices.isEmpty()) {
                                        Map<String, Object> delta = (Map) choices.get(0).get("delta");
                                        if (delta != null) {
                                            String content = (String) delta.get("content");
                                            if (content != null && !content.isEmpty()) {
                                                finalContent.append(content);
                                                // 逐 chunk emit，实现流式逐字输出
                                                String jsonDelta = "{\"delta\":" + objectMapper.writeValueAsString(content) + "}";
                                                sink.tryEmitNext(ServerSentEvent.<String>builder().event("text").data(jsonDelta).build());
                                            }
                                        }
                                    }
                                    // 提取 usage（可能最后一个 chunk 才有）
                                    Map<String, Object> usage = (Map) ev.get("usage");
                                    if (usage != null) {
                                        Object in = usage.get("prompt_tokens");
                                        Object out = usage.get("completion_tokens");
                                        if (in instanceof Number n) inputTokens = n.intValue();
                                        if (out instanceof Number n) outputTokens = n.intValue();
                                    }
                                } catch (Exception parseErr) {
                                    log.debug("[NativeVision] SSE parse skip: {}", json.substring(0, Math.min(80, json.length())));
                                }
                            }
                            sseEvent.setLength(0);
                        } else {
                            sseEvent.append(line).append("\n");
                        }
                    }
                }

                // === 5.5 日志汇总 ===
                String rawStr = finalContent.toString();
                int rawNewlines = rawStr.length() - rawStr.replace("\n", "").length();
                log.info("[NativeVision] content={} chars ({} newlines)", rawStr.length(), rawNewlines);

                // === 6. 发 done ===
                long latency = System.currentTimeMillis() - startMs;
                log.info("[NativeVision] done, content={} chars, input={}, output={}, latency={}ms",
                        finalContent.length(), inputTokens, outputTokens, latency);
                String preview = finalContent.length() > 300 ? finalContent.substring(0, 300) : finalContent.toString();
                log.info("[NativeVision] content preview: {}", preview.replace("\n", "\\n"));

                // done 事件：带 finalContent + token + latency，由 ChatController 统一存库
                Map<String, Object> donePayload = new java.util.HashMap<>();
                donePayload.put("model", "deepseek-flash");
                donePayload.put("finalContent", finalContent.toString());
                donePayload.put("latencyMs", latency);
                // 两种 token 格式都发，兼容 ChatController 和前端
                Map<String, Object> usage = new java.util.HashMap<>();
                usage.put("input_tokens", inputTokens);
                usage.put("output_tokens", outputTokens);
                donePayload.put("usage", usage);
                donePayload.put("inputTokens", inputTokens);
                donePayload.put("outputTokens", outputTokens);

                sink.tryEmitNext(ServerSentEvent.<String>builder().event("done").data(
                        objectMapper.writeValueAsString(donePayload)).build());
                sink.tryEmitComplete();

            } catch (Exception e) {
                log.error("[NativeVision] exception: {}", e.getMessage(), e);
                sink.tryEmitNext(ServerSentEvent.<String>builder().event("error").data(
                        "{\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}").build());
                sink.tryEmitComplete();
            }
        }, "native-vision").start();

        return sink.asFlux();
    }

    /** Agent 运行期间收集的元数据，done 事件和 DB 写入都用它 */
    static class Metadata {
        int inputTokens;
        int outputTokens;
        long latencyMs;
        String model;
        final List<Map<String, Object>> toolCalls = new ArrayList<>();
    }
}

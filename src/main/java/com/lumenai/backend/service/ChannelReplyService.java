package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumenai.backend.entity.ChannelAccount;
import com.lumenai.backend.entity.ChannelMessage;
import com.lumenai.backend.mapper.ChannelMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 通道消息 AI 自动回复（流式）
 *
 * 微信用户或客户端用户发来消息后：
 *   1. 拼装最近对话历史 → ChatModel.stream 逐片生成，写入 ReplyStreamRegistry（前端 SSE 逐字展示）
 *   2. 完成后回复作为 outbound 入库（agent_executed=true）
 *   3. 通过 iLink Bot API 发回微信
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelReplyService {

    private final ChannelMessageMapper messageMapper;
    private final IlinkBotClient ilinkBotClient;
    private final ReplyStreamRegistry registry;
    private final ObjectProvider<ChatModel> chatModelProvider;

    private static final String SYSTEM_PROMPT =
            "你是 Lumen AI 助手，正在通过微信与用户对话。" +
            "请用简洁、友好、口语化的中文回答，控制在 200 字以内；" +
            "如果是编程或技术问题，可以给出关键代码或步骤，但不要长篇大论。";

    /** 异步触发回复 */
    public void replyAsync(ChannelAccount account, ChannelMessage inbound) {
        if (account == null || inbound == null) return;
        String text = inbound.getContent();
        if (text == null || text.isBlank()) return; // 非文本（表情/图片）暂不处理

        final String streamKey = ReplyStreamRegistry.key(account.getId(), inbound.getPeerId());
        CompletableFuture.runAsync(() -> {
            try {
                doReply(account, inbound, streamKey, null);
            } catch (Exception e) {
                log.error("通道 AI 回复失败: peerId={}", inbound.getPeerId(), e);
                registry.fail(streamKey, e.getMessage());
            }
        });
    }

    /**
     * 重新生成：以指定用户消息为输入重新生成 AI 回复。
     * 只取该消息及之前的上下文，避免模型看到旧回答而重复；用户消息本身不重新发送到微信。
     */
    public void regenerateAsync(ChannelAccount account, ChannelMessage userMessage) {
        if (account == null || userMessage == null) return;
        final String streamKey = ReplyStreamRegistry.key(account.getId(), userMessage.getPeerId());
        CompletableFuture.runAsync(() -> {
            try {
                doReply(account, userMessage, streamKey, userMessage.getId());
            } catch (Exception e) {
                log.error("通道 AI 重新生成失败: peerId={}", userMessage.getPeerId(), e);
                registry.fail(streamKey, e.getMessage());
            }
        });
    }

    private void doReply(ChannelAccount account, ChannelMessage inbound, String streamKey, Long maxMessageId) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            log.warn("ChatModel 未配置，跳过 AI 自动回复");
            registry.fail(streamKey, "ChatModel 未配置");
            return;
        }

        // 1. 取最近 11 条对话（含本条），按时间正序；重新生成时只取目标消息之前的上下文
        LambdaQueryWrapper<ChannelMessage> historyQuery = new LambdaQueryWrapper<ChannelMessage>()
                .eq(ChannelMessage::getAccountId, account.getId())
                .eq(ChannelMessage::getPeerId, inbound.getPeerId())
                .orderByDesc(ChannelMessage::getCreatedAt)
                .last("LIMIT 11");
        if (maxMessageId != null) {
            historyQuery.le(ChannelMessage::getId, maxMessageId);
        }
        List<ChannelMessage> history = messageMapper.selectList(historyQuery);
        Collections.reverse(history);

        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(SYSTEM_PROMPT));
        for (ChannelMessage m : history) {
            if (m.getContent() == null || m.getContent().isBlank()) continue;
            // 角色映射：
            //   inbound（微信用户发来）= 用户
            //   outbound + agentExecuted=false（客户端用户手动发）= 用户
            //   outbound + agentExecuted=true（AI 回复）= 助手
            boolean fromUser = "inbound".equals(m.getDirection()) || !Boolean.TRUE.equals(m.getAgentExecuted());
            if (fromUser) {
                promptMessages.add(new UserMessage(m.getContent()));
            } else {
                promptMessages.add(new AssistantMessage(m.getContent()));
            }
        }

        // 2. 流式调模型，逐片写入注册表
        log.info("生成 AI 回复(流式): accountId={}, peerId={}, 历史{}条", account.getId(), inbound.getPeerId(), history.size());
        ReplyStreamRegistry.State state = registry.start(streamKey);
        StringBuilder full = new StringBuilder();
        int inputTokens = 0, outputTokens = 0;
        try {
            Iterable<ChatResponse> chunks = chatModel.stream(new Prompt(promptMessages)).toIterable();
            for (ChatResponse cr : chunks) {
                if (cr.getResult() == null || cr.getResult().getOutput() == null) continue;
                String delta = cr.getResult().getOutput().getText();
                if (delta != null && !delta.isEmpty()) {
                    full.append(delta);
                    state.append(delta);
                }
                // usage 通常只在最后一个 chunk 返回，滚动覆盖即可拿到最终值
                if (cr.getMetadata() != null && cr.getMetadata().getUsage() != null) {
                    Usage usage = cr.getMetadata().getUsage();
                    Integer pt = usage.getPromptTokens();
                    Integer ct = usage.getCompletionTokens();
                    if (pt != null) inputTokens = pt;
                    if (ct != null) outputTokens = ct;
                }
            }
        } catch (Exception e) {
            registry.fail(streamKey, e.getMessage());
            throw e;
        }

        String reply = full.toString().trim();
        if (reply.isEmpty()) {
            log.warn("AI 回复为空，跳过");
            registry.fail(streamKey, "回复为空");
            return;
        }

        // 3. outbound 入库
        ChannelMessage out = new ChannelMessage();
        out.setUserId(account.getUserId());
        out.setAccountId(account.getId());
        out.setChannel(account.getChannel());
        out.setPeerId(inbound.getPeerId());
        out.setDirection("outbound");
        out.setMessageType("text");
        out.setContent(reply);
        out.setContextToken(inbound.getContextToken());
        out.setIsRead(true);
        out.setAgentExecuted(true);
        out.setInputTokens(inputTokens);
        out.setOutputTokens(outputTokens);
        out.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(out);

        // 4. 通过 iLink 发回微信（失败不影响客户端展示）
        boolean sent = false;
        try {
            sent = ilinkBotClient.sendText(
                    account.getBaseUrl(),
                    account.getBotToken(),
                    account.getIlinkBotId(),
                    inbound.getPeerId(),
                    reply,
                    inbound.getContextToken());
        } catch (Exception e) {
            log.warn("iLink 发送失败: {}", e.getMessage());
        }
        log.info("AI 回复已{}: peerId={}, len={}, tokens={}/{}",
                sent ? "发送" : "入库但发送失败", inbound.getPeerId(), reply.length(), inputTokens, outputTokens);

        state.setUsage(inputTokens, outputTokens);
        registry.finish(streamKey);
    }
}

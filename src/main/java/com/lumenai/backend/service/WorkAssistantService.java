package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.entity.ChannelAccount;
import com.lumenai.backend.entity.ChannelMessage;
import com.lumenai.backend.mapper.ChannelAccountMapper;
import com.lumenai.backend.mapper.ChannelMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 办公助理 Service（iLink Bot 架构）
 *
 * 消息链路：
 *   Electron 主进程（直连 iLink HTTP API）
 *     → 登录成功：POST /work-assistant/accounts/confirm  → 写 channel_accounts
 *     → 收到微信消息：POST /work-assistant/messages/inbound → 写 channel_messages
 *   前端 ChatArea
 *     → 发送消息：POST /work-assistant/messages → 写 channel_messages + 调 iLink API（经 Electron）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkAssistantService {

    private final ChannelAccountMapper accountMapper;
    private final ChannelMessageMapper messageMapper;
    private final ChannelReplyService channelReplyService;
    private final IlinkBotClient ilinkBotClient;

    /** 事务提交后再异步触发 AI 回复，保证异步线程能读到刚入库的 inbound */
    private void triggerAutoReply(ChannelAccount account, ChannelMessage inbound) {
        if (inbound == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    channelReplyService.replyAsync(account, inbound);
                }
            });
        } else {
            channelReplyService.replyAsync(account, inbound);
        }
    }

    // === 通道账号 ===

    /** 列出当前用户所有通道绑定 */
    public List<ChannelAccount> listAccounts() {
        Long userId = UserContext.get();
        return accountMapper.selectList(new LambdaQueryWrapper<ChannelAccount>()
                .eq(ChannelAccount::getUserId, userId)
                .orderByDesc(ChannelAccount::getUpdatedAt));
    }

    /**
     * 确认绑定完成
     * @param userId 用户 ID（调用方传入，不再从 UserContext 取）
     */
    @Transactional
    public ChannelAccount confirmBind(Long userId, String channel, String botToken, String ilinkBotId, String ilinkUserId, String baseUrl) {
        LocalDateTime now = LocalDateTime.now();

        ChannelAccount account = accountMapper.selectOne(new LambdaQueryWrapper<ChannelAccount>()
                .eq(ChannelAccount::getUserId, userId)
                .eq(ChannelAccount::getChannel, channel));

        if (account == null) {
            account = new ChannelAccount();
            account.setUserId(userId);
            account.setChannel(channel);
            account.setCreatedAt(now);
        }

        // iLink 凭据
        account.setBotToken(botToken);
        account.setIlinkBotId(ilinkBotId);
        account.setIlinkUserId(ilinkUserId);
        account.setBaseUrl(baseUrl);
        // accountId 即为 ilinkBotId（通道侧账号标识）
        account.setAccountId(ilinkBotId);
        account.setBindStatus("bound");
        account.setUpdatedAt(now);

        if (account.getId() == null) {
            try {
                accountMapper.insert(account);
            } catch (DuplicateKeyException e) {
                // 并发下另一个请求刚 insert 成功，转为 update
                log.info("confirmBind 遇到唯一键冲突，转为 update: userId={}, channel={}", userId, channel);
                account = accountMapper.selectOne(new LambdaQueryWrapper<ChannelAccount>()
                        .eq(ChannelAccount::getUserId, userId)
                        .eq(ChannelAccount::getChannel, channel));
                if (account != null) {
                    account.setBotToken(botToken);
                    account.setIlinkBotId(ilinkBotId);
                    account.setIlinkUserId(ilinkUserId);
                    account.setBaseUrl(baseUrl);
                    account.setAccountId(ilinkBotId);
                    account.setBindStatus("bound");
                    account.setUpdatedAt(now);
                    accountMapper.updateById(account);
                }
            }
        } else {
            accountMapper.updateById(account);
        }
        log.info("通道绑定确认: userId={}, channel={}, botId={}", userId, channel, ilinkBotId);
        return account;
    }

    /** 解绑通道 */
    @Transactional
    public void unbind(String channel) {
        Long userId = UserContext.get();
        ChannelAccount account = accountMapper.selectOne(new LambdaQueryWrapper<ChannelAccount>()
                .eq(ChannelAccount::getUserId, userId)
                .eq(ChannelAccount::getChannel, channel));
        if (account != null) {
            account.setBindStatus("unbound");
            account.setBotToken(null);
            account.setAccountId(null);
            account.setIlinkBotId(null);
            account.setIlinkUserId(null);
            account.setBaseUrl(null);
            account.setUpdatedAt(LocalDateTime.now());
            accountMapper.updateById(account);
            log.info("通道解绑: userId={}, channel={}", userId, channel);
        }
    }

    /** 查询绑定状态 */
    public ChannelAccount getAccount(String channel) {
        Long userId = UserContext.get();
        return accountMapper.selectOne(new LambdaQueryWrapper<ChannelAccount>()
                .eq(ChannelAccount::getUserId, userId)
                .eq(ChannelAccount::getChannel, channel));
    }

    /** 校验通道账号归属当前登录用户 */
    public ChannelAccount getOwnedAccount(Long userId, Long accountId) {
        if (userId == null || accountId == null) return null;
        ChannelAccount account = accountMapper.selectById(accountId);
        if (account != null && userId.equals(account.getUserId())) return account;
        return null;
    }

    /** 根据 id 查账号（内部用，不限 userId） */
    public ChannelAccount getAccountById(Long accountId) {
        return accountMapper.selectById(accountId);
    }

    /** 根据 ilinkUserId 查账号（noAuth 模式用） */
    public ChannelAccount findByIlinkUserId(String ilinkUserId) {
        if (ilinkUserId == null || ilinkUserId.isBlank()) return null;
        return accountMapper.selectOne(new LambdaQueryWrapper<ChannelAccount>()
                .eq(ChannelAccount::getIlinkUserId, ilinkUserId)
                .last("LIMIT 1"));
    }

    /**
     * 标记通道会话失效（iLink 返回 ret=-14，说明该 bot 已在别处重新扫码绑定，本机凭据作废）。
     * 由 Electron 主进程在无 JWT 场景下调用，按 ilinkUserId 反查账号。
     *
     * @return 受影响的账号，未找到返回 null
     */
    @Transactional
    public ChannelAccount markSessionExpired(String ilinkUserId) {
        ChannelAccount account = findByIlinkUserId(ilinkUserId);
        if (account == null) return null;
        account.setBindStatus("offline");
        account.setBotToken(null);
        account.setBaseUrl(null);
        account.setUpdatedAt(LocalDateTime.now());
        accountMapper.updateById(account);
        log.info("通道会话失效，已标记解绑: userId={}, channel={}, ilinkUserId={}",
                account.getUserId(), account.getChannel(), ilinkUserId);
        return account;
    }

    /**
     * 校验当前用户绑定的 token 是否仍然有效（主动探测 iLink）。
     *
     * 必要性：Electron 侧只有在「本机凭据存在且监听在跑」时才能通过 ret=-14 感知失效；
     * 若本机凭据已丢失（例如换机、清缓存），就永远发现不了，服务端状态会一直停在 bound。
     * 这里用库里存的 token 主动探一次，作为不依赖客户端的兜底。
     *
     * @return true=有效；false=已失效（并已标记解绑）
     */
    public boolean verifyBinding(String channel) {
        Long userId = UserContext.get();
        if (userId == null) return false;
        ChannelAccount account = accountMapper.selectOne(new LambdaQueryWrapper<ChannelAccount>()
                .eq(ChannelAccount::getUserId, userId)
                .eq(ChannelAccount::getChannel, channel));
        if (account == null || !"bound".equals(account.getBindStatus())) return false;
        if (account.getBotToken() == null || account.getBotToken().isBlank()) {
            // 无 token 却标记为 bound，属脏数据，直接纠正
            markOffline(account);
            return false;
        }

        boolean alive = ilinkBotClient.probeSession(account.getBaseUrl(), account.getBotToken());
        if (!alive) {
            markOffline(account);
            log.info("主动探测发现绑定已失效，已标记解绑: userId={}, channel={}", userId, channel);
        }
        return alive;
    }

    /** 置为未绑定并清空凭据 */
    @Transactional
    public void markOffline(ChannelAccount account) {
        account.setBindStatus("offline");
        account.setBotToken(null);
        account.setBaseUrl(null);
        account.setUpdatedAt(LocalDateTime.now());
        accountMapper.updateById(account);
    }

    // === 消息 ===

    /** 当前登录用户的会话列表（Controller 入口） */
    public List<ChannelMessage> listCurrentUserSessions() {
        Long userId = UserContext.get();
        if (userId == null) return List.of();
        ChannelAccount acc = accountMapper.selectOne(new LambdaQueryWrapper<ChannelAccount>()
                .eq(ChannelAccount::getUserId, userId)
                .eq(ChannelAccount::getChannel, "weixin")
                .last("LIMIT 1"));
        if (acc == null) return List.of();
        return messageMapper.selectList(new LambdaQueryWrapper<ChannelMessage>()
                .eq(ChannelMessage::getAccountId, acc.getId())
                .orderByDesc(ChannelMessage::getCreatedAt));
    }

    /** 当前登录用户的消息列表（Controller 入口） */
    public List<ChannelMessage> listCurrentUserMessages(String peerId) {
        Long userId = UserContext.get();
        if (userId == null) return List.of();
        ChannelAccount acc = accountMapper.selectOne(new LambdaQueryWrapper<ChannelAccount>()
                .eq(ChannelAccount::getUserId, userId)
                .eq(ChannelAccount::getChannel, "weixin")
                .last("LIMIT 1"));
        if (acc == null) return List.of();
        if (peerId != null && !peerId.isBlank()) {
            return messageMapper.selectList(new LambdaQueryWrapper<ChannelMessage>()
                    .eq(ChannelMessage::getAccountId, acc.getId())
                    .eq(ChannelMessage::getPeerId, peerId)
                    .orderByAsc(ChannelMessage::getCreatedAt));
        }
        return messageMapper.selectList(new LambdaQueryWrapper<ChannelMessage>()
                .eq(ChannelMessage::getAccountId, acc.getId())
                .orderByDesc(ChannelMessage::getCreatedAt)
                .last("LIMIT 100"));
    }

    /** 列出某通道的消息（按 peer 分组查询） */
    public List<ChannelMessage> listMessages(Long accountId, String peerId) {
        Long userId = UserContext.get();
        log.info("listMessages: accountId={}, userId={}, peerId={}", accountId, userId, peerId);
        if (peerId != null && !peerId.isBlank()) {
            return messageMapper.selectList(new LambdaQueryWrapper<ChannelMessage>()
                    .eq(userId != null, ChannelMessage::getUserId, userId)
                    .eq(ChannelMessage::getAccountId, accountId)
                    .eq(ChannelMessage::getPeerId, peerId)
                    .orderByAsc(ChannelMessage::getCreatedAt));
        }
        return listRecentMessages(100);
    }

    /** 列出最近消息（办公助理面板） */
    public List<ChannelMessage> listRecentMessages(int limit) {
        Long userId = UserContext.get();
        return messageMapper.selectList(new LambdaQueryWrapper<ChannelMessage>()
                .eq(userId != null, ChannelMessage::getUserId, userId)
                .orderByDesc(ChannelMessage::getCreatedAt)
                .last("LIMIT " + limit));
    }

    /**
     * 列出通道会话（按 peer 分组的最后一条消息）
     * 用于聊天页面左侧会话列表
     */
    public List<ChannelMessage> listSessions(Long accountId) {
        Long userId = UserContext.get();
        log.info("listSessions: accountId={}, userId={}", accountId, userId);
        // 取该账号下每个 peer 的最新一条消息
        return messageMapper.selectList(new LambdaQueryWrapper<ChannelMessage>()
                .eq(userId != null, ChannelMessage::getUserId, userId)
                .eq(ChannelMessage::getAccountId, accountId)
                .orderByDesc(ChannelMessage::getCreatedAt));
    }

    /**
     * 写入 inbound 消息（Electron 主进程收到微信消息后回调）
     * 去重 + 自动关联 accountId/userId
     */
    @Transactional
    public ChannelMessage saveInboundMessage(String channel, String peerId, String content,
                                              String ilinkMsgId, String contextToken) {
        // 去重：iLink 的 message_id 是唯一的
        if (ilinkMsgId != null) {
            Long existing = messageMapper.selectCount(new LambdaQueryWrapper<ChannelMessage>()
                    .eq(ChannelMessage::getIlinkMsgId, ilinkMsgId));
            if (existing != null && existing > 0) {
                log.debug("消息已存在，跳过: ilinkMsgId={}", ilinkMsgId);
                return null;
            }
        }

        // 找到该用户的对应 channel_account
        Long userId = UserContext.get();
        ChannelAccount account = accountMapper.selectOne(new LambdaQueryWrapper<ChannelAccount>()
                .eq(ChannelAccount::getUserId, userId)
                .eq(ChannelAccount::getChannel, channel));
        if (account == null) {
            log.warn("收到 inbound 消息但未找到 channel_account: userId={}, channel={}", userId, channel);
            return null;
        }

        ChannelMessage msg = new ChannelMessage();
        msg.setUserId(userId);
        msg.setAccountId(account.getId());
        msg.setChannel(channel);
        msg.setPeerId(peerId);
        msg.setDirection("inbound");
        msg.setMessageType("text");
        msg.setContent(content);
        msg.setIlinkMsgId(ilinkMsgId);
        msg.setContextToken(contextToken);
        msg.setIsRead(false);
        msg.setAgentExecuted(false);
        msg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(msg);

        // 更新账号最后活跃时间
        account.setLastPollAt(LocalDateTime.now());
        accountMapper.updateById(account);

        log.info("inbound 消息入库: accountId={}, peerId={}, content={}", account.getId(), peerId, content.length() > 50 ? content.substring(0, 50) + "..." : content);
        triggerAutoReply(account, msg);
        return msg;
    }

    /**
     * 写入 inbound 消息（无 UserContext 场景，如 Electron 内部直调）
     * 通过 ilinkUserId 反查 userId
     */
    @Transactional
    public ChannelMessage saveInboundMessageNoAuth(String channel, String ilinkUserId, String peerId,
                                                    String content, String ilinkMsgId, String contextToken) {
        Long existing = messageMapper.selectCount(new LambdaQueryWrapper<ChannelMessage>()
                .eq(ChannelMessage::getIlinkMsgId, ilinkMsgId));
        if (existing != null && existing > 0) return null;

        ChannelAccount account = accountMapper.selectOne(new LambdaQueryWrapper<ChannelAccount>()
                .eq(ChannelAccount::getChannel, channel)
                .eq(ChannelAccount::getIlinkUserId, ilinkUserId));
        if (account == null) {
            log.warn("inbound: 未找到 channel_account for ilinkUserId={}", ilinkUserId);
            return null;
        }

        ChannelMessage msg = new ChannelMessage();
        msg.setUserId(account.getUserId());
        msg.setAccountId(account.getId());
        msg.setChannel(channel);
        msg.setPeerId(peerId);
        msg.setDirection("inbound");
        msg.setMessageType("text");
        msg.setContent(content);
        msg.setIlinkMsgId(ilinkMsgId);
        msg.setContextToken(contextToken);
        msg.setIsRead(false);
        msg.setAgentExecuted(false);
        msg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(msg);

        account.setLastPollAt(LocalDateTime.now());
        accountMapper.updateById(account);

        log.info("inbound(noAuth) 消息入库: accountId={}, peerId={}", account.getId(), peerId);
        triggerAutoReply(account, msg);
        return msg;
    }

    /**
     * 发送消息（前端 → 后端 → Electron iLink API）
     * 后端只做入库，实际发送由前端调 Electron IPC 完成
     */
    @Transactional
    public ChannelMessage saveOutboundMessage(Long accountId, String peerId, String content) {
        Long userId = UserContext.get();
        ChannelAccount account = accountMapper.selectById(accountId);
        if (account == null || !account.getUserId().equals(userId)) {
            throw new RuntimeException("通道账号不存在");
        }
        if (!"bound".equals(account.getBindStatus())) {
            throw new RuntimeException("通道未绑定");
        }

        ChannelMessage msg = new ChannelMessage();
        msg.setUserId(userId);
        msg.setAccountId(accountId);
        msg.setChannel(account.getChannel());
        msg.setPeerId(peerId);
        msg.setDirection("outbound");
        msg.setMessageType("text");
        msg.setContent(content);
        msg.setIsRead(true);
        msg.setAgentExecuted(false);
        msg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(msg);
        log.info("outbound 消息入库: accountId={}, peerId={}", accountId, peerId);
        // 用户在客户端主动提问（agentExecuted=false），异步触发 AI 回复
        triggerAutoReply(account, msg);
        return msg;
    }

    /** 标记已读 */
    public void markRead(Long accountId, String peerId) {
        messageMapper.update(null, new LambdaUpdateWrapper<ChannelMessage>()
                .eq(ChannelMessage::getAccountId, accountId)
                .eq(ChannelMessage::getPeerId, peerId)
                .eq(ChannelMessage::getIsRead, false)
                .set(ChannelMessage::getIsRead, true));
    }

    /**
     * 查某会话最近一条用户消息（微信发来的 inbound，或客户端手动发出的 outbound），
     * 用于「重新生成」时重新作为提问输入。
     */
    public ChannelMessage findLastUserMessage(Long accountId, String peerId) {
        if (accountId == null || peerId == null) return null;
        return messageMapper.selectOne(new LambdaQueryWrapper<ChannelMessage>()
                .eq(ChannelMessage::getAccountId, accountId)
                .eq(ChannelMessage::getPeerId, peerId)
                .and(w -> w.eq(ChannelMessage::getDirection, "inbound")
                        .or()
                        .eq(ChannelMessage::getAgentExecuted, false))
                .orderByDesc(ChannelMessage::getCreatedAt)
                .last("LIMIT 1"));
    }
}

package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumenai.backend.common.exception.BusinessException;
import com.lumenai.backend.dto.SessionCreateRequest;
import com.lumenai.backend.dto.SessionResponse;
import com.lumenai.backend.dto.SessionUpdateRequest;
import com.lumenai.backend.entity.Message;
import com.lumenai.backend.entity.ScheduleRun;
import com.lumenai.backend.entity.Session;
import com.lumenai.backend.mapper.MessageMapper;
import com.lumenai.backend.mapper.ScheduleRunMapper;
import com.lumenai.backend.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final ScheduleRunMapper scheduleRunMapper;

    /** 列出当前用户的会话 */
    public List<SessionResponse> listByUser(Long userId) {
        List<Session> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<Session>()
                        .eq(Session::getUserId, userId)
                        .eq(Session::getStatus, "active")
                        .orderByDesc(Session::getUpdatedAt)
        );
        if (sessions.isEmpty()) return Collections.emptyList();

        // 批量查询哪些 sessionId 是由自动化任务生成的
        List<Long> sessionIds = sessions.stream().map(Session::getId).collect(Collectors.toList());
        List<ScheduleRun> runs = scheduleRunMapper.selectList(
                new LambdaQueryWrapper<ScheduleRun>()
                        .in(ScheduleRun::getSessionId, sessionIds)
        );
        Set<Long> scheduleRunSessionIds = new HashSet<>();
        for (ScheduleRun r : runs) {
            if (r.getSessionId() != null) scheduleRunSessionIds.add(r.getSessionId());
        }

        return sessions.stream()
                .map(s -> SessionResponse.from(s, getLastMessagePreview(s.getId()), scheduleRunSessionIds.contains(s.getId())))
                .collect(Collectors.toList());
    }

    /** 创建会话 */
    public Session create(Long userId, SessionCreateRequest req) {
        if (userId == null) throw new BusinessException(401, "未登录");
        Session s = new Session();
        s.setUserId(userId);
        s.setTitle(req.getTitle() != null ? req.getTitle() : "新会话");
        s.setProjectPath(req.getProjectPath());
        s.setMode(req.getMode() != null ? req.getMode() : "default");
        s.setStatus("active");
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(s);
        return s;
    }

    /** 获取单个会话（校验归属） */
    public Session getOrThrow(Long userId, Long sessionId) {
        Session s = sessionMapper.selectById(sessionId);
        if (s == null) throw new BusinessException("会话不存在");
        if (!s.getUserId().equals(userId)) throw new BusinessException("无权访问此会话");
        return s;
    }

    /** 更新会话 */
    public Session update(Long userId, Long sessionId, SessionUpdateRequest req) {
        Session s = getOrThrow(userId, sessionId);
        if (req.getTitle() != null) s.setTitle(req.getTitle());
        if (req.getStatus() != null) s.setStatus(req.getStatus());
        s.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(s);
        return s;
    }

    /** 删除会话（级联删除对应的执行历史记录） */
    @Transactional
    public void delete(Long userId, Long sessionId) {
        getOrThrow(userId, sessionId);
        // 级联删除自动化执行历史
        scheduleRunMapper.delete(
                new LambdaQueryWrapper<ScheduleRun>()
                        .eq(ScheduleRun::getSessionId, sessionId)
        );
        sessionMapper.deleteById(sessionId);
        // messages 级联删除已由外键 ON DELETE CASCADE 处理
    }

    /** 列出会话下的消息 */
    public List<Message> listMessages(Long userId, Long sessionId) {
        getOrThrow(userId, sessionId);
        return messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getSessionId, sessionId)
                        .eq(Message::getUserId, userId)
                        .orderByAsc(Message::getId)
        );
    }

    /** 取最后一条消息预览 */
    private String getLastMessagePreview(Long sessionId) {
        Message last = messageMapper.selectOne(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getSessionId, sessionId)
                        .eq(Message::getRole, "user")
                        .orderByDesc(Message::getId)
                        .last("LIMIT 1")
        );
        if (last == null || last.getContent() == null) return null;
        String c = last.getContent().trim();
        return c.length() > 60 ? c.substring(0, 60) + "…" : c;
    }
}

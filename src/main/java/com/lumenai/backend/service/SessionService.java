package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumenai.backend.common.exception.BusinessException;
import com.lumenai.backend.common.exception.ErrorCode;
import com.lumenai.backend.dto.SessionCreateRequest;
import com.lumenai.backend.dto.SessionResponse;
import com.lumenai.backend.dto.SessionUpdateRequest;
import com.lumenai.backend.entity.Message;
import com.lumenai.backend.entity.Schedule;
import com.lumenai.backend.entity.ScheduleRun;
import com.lumenai.backend.entity.Session;
import com.lumenai.backend.entity.Workspace;
import com.lumenai.backend.mapper.MessageMapper;
import com.lumenai.backend.mapper.ScheduleMapper;
import com.lumenai.backend.mapper.ScheduleRunMapper;
import com.lumenai.backend.mapper.SessionMapper;
import com.lumenai.backend.mapper.WorkspaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;
    private final ScheduleRunMapper scheduleRunMapper;
    /** 只用来判断空间是否被自动化任务占用（孤儿空间清理），不要在这里写任务业务逻辑 */
    private final ScheduleMapper scheduleMapper;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceService workspaceService;

    /**
     * 列出当前用户的会话。
     *
     * 🔴 **不能只查 `status='active'`**（2026-09-22 事故：用户归档后退出重登，归档记录凭空消失）。
     * 之前只返回 active，导致「归档」与「置顶」的会话在列表里根本拿不到：
     * 前端 `sessionStore` 是这些状态的唯一数据源 —— 侧栏要渲染置顶板块、设置页「数据管理」
     * 要渲染已归档任务，状态被服务端过滤掉后它们就彻底不可见（而且看起来像数据丢了）。
     * 过滤职责归**前端**（按 status 分组渲染），服务端只负责返回该用户的全部会话。
     */
    public List<SessionResponse> listByUser(Long userId) {
        List<Session> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<Session>()
                        .eq(Session::getUserId, userId)
                        .in(Session::getStatus, "active", "pinned", "archived")
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

        // 空间名批量查（侧栏分组要用）：一次 IN 查询，不要逐条 selectById
        Map<Long, String> workspaceNames = workspaceNames(sessions);

        return sessions.stream()
                .map(s -> SessionResponse.from(s, getLastMessagePreview(s.getId()),
                        scheduleRunSessionIds.contains(s.getId()),
                        s.getWorkspaceId() == null ? null : workspaceNames.get(s.getWorkspaceId())))
                .collect(Collectors.toList());
    }

    /** 会话绑定的空间名（批量，缺失的 id 不出现在结果里） */
    private Map<Long, String> workspaceNames(List<Session> sessions) {
        Set<Long> ids = sessions.stream()
                .map(Session::getWorkspaceId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return Collections.emptyMap();
        try {
            return workspaceMapper.selectList(new LambdaQueryWrapper<Workspace>().in(Workspace::getId, ids))
                    .stream()
                    .collect(Collectors.toMap(Workspace::getId, Workspace::getName, (a, b) -> a));
        } catch (Exception e) {
            // 空间名只是展示用，查不到不该让会话列表整体失败
            return Collections.emptyMap();
        }
    }

    /** 创建会话 */
    public Session create(Long userId, SessionCreateRequest req) {
        return create(userId, req, null);
    }

    /**
     * 创建会话（带设备维度）。
     *
     * <p>绑定的工作空间必须是「属于当前用户 <b>且</b> 在当前设备上」的。
     * ⚠️ <b>绑不上时抛 404，绝不静默降级</b>（2026-09-21 修正）：早期版本把「跨设备 / 已移除」
     * 一律按无空间创建，本意是「别让用户开不了会话」，实际却把异常状态伪装成正常状态 ——
     * 前端缓存里已被删掉的空间、换设备后残留的选中值，都会静默变成「无空间会话」，
     * 用户看到的是「我明明选了工作空间，却没绑上」，且没有任何提示。现在显式报错，
     * 由前端提示并清掉失效的选中值。</p>
     *
     * <p>调用方约定：只有「用户显式指定的空间」才会走到这里。{@code ScheduleRunner} 这类
     * 后台链路用 {@link WorkspaceService#find} 宽松取空间，取不到时传 {@code null}，
     * 因此不会因为空间被移除而让自动化任务失败（FR-21 行为不变）。</p>
     */
    public Session create(Long userId, SessionCreateRequest req, String deviceId) {
        if (userId == null) throw new BusinessException(401, "未登录");
        Session s = new Session();
        s.setUserId(userId);
        s.setTitle(req.getTitle() != null ? req.getTitle() : "新会话");
        Long workspaceId = req.getWorkspaceId();
        if (workspaceId != null) {
            Workspace ws = workspaceService.resolveForDevice(userId, workspaceId, deviceId);
            if (ws == null) {
                log.warn("建会话被拒：所选工作空间不可用 workspaceId={} userId={} device={}",
                        workspaceId, userId, deviceId);
                throw new BusinessException(404, ErrorCode.WORKSPACE_NOT_FOUND,
                        "所选工作空间不存在或不在当前设备，请重新选择工作空间");
            }
            s.setWorkspaceId(ws.getId());
        }
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
        // 工作空间回填（FR-10 迁移引导）：**单向** null → 某空间，且必须是自己名下的空间。
        // 刻意不支持换绑 / 解绑：会话的空间在创建后锁定（FR-12），换目录会让模型手里
        // 已经建立的相对路径语义整体偏移到另一个目录。
        if (req.getWorkspaceId() != null) {
            if (s.getWorkspaceId() == null) {
                workspaceService.getOrThrow(userId, req.getWorkspaceId());
                s.setWorkspaceId(req.getWorkspaceId());
                log.info("会话工作空间回填 sessionId={} workspaceId={}", sessionId, req.getWorkspaceId());
            } else if (!s.getWorkspaceId().equals(req.getWorkspaceId())) {
                throw new BusinessException(409, ErrorCode.SESSION_WORKSPACE_LOCKED,
                        "该会话已绑定工作空间，创建后不可更换");
            }
        }
        s.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(s);
        return s;
    }

    /**
     * 删除会话（级联删除对应的执行历史记录）。
     *
     * <p>🔴 若被删的是某个工作空间下<b>最后一条</b>会话，则一并移除该空间的登记
     * （2026-09-21 需求）：空间是「会话的分组」，分组空了还留着它，选择器与侧栏就会一直
     * 躺着一个点进去什么都没有的目录。</p>
     *
     * <p>两个例外，刻意不删：① 空间下还有别的会话（含已归档 —— 行还在，随时可能被恢复）；
     * ② 空间<b>被自动化任务引用</b>（`schedules.workspace_id`）—— 那是任务的执行目录，
     * 任务还在就删空间，会让它下次触发时取不到目录、悄悄退回「无空间」执行。</p>
     *
     * <p>⚠️ 只删元数据行，<b>磁盘目录一律不动</b>（与 {@link WorkspaceService#delete} 同口径）。</p>
     */
    @Transactional
    public void delete(Long userId, Long sessionId) {
        Session s = getOrThrow(userId, sessionId);
        Long workspaceId = s.getWorkspaceId();
        // 级联删除自动化执行历史
        scheduleRunMapper.delete(
                new LambdaQueryWrapper<ScheduleRun>()
                        .eq(ScheduleRun::getSessionId, sessionId)
        );
        sessionMapper.deleteById(sessionId);
        // messages 级联删除已由外键 ON DELETE CASCADE 处理
        removeWorkspaceIfOrphaned(userId, workspaceId);
    }

    /**
     * 空间下已无任何会话、且没有被自动化任务引用时，移除空间登记。
     *
     * <p>放在 {@link #delete} 旁边而不是 {@link WorkspaceService} 里：触发点只有「会话被删」
     * 这一个，写在一起才看得到完整因果。{@code workspaceId} 为 null（无空间会话）直接返回。</p>
     */
    private void removeWorkspaceIfOrphaned(Long userId, Long workspaceId) {
        if (workspaceId == null) return;
        Long left = sessionMapper.selectCount(
                new LambdaQueryWrapper<Session>()
                        .eq(Session::getUserId, userId)
                        .eq(Session::getWorkspaceId, workspaceId));
        if (left != null && left > 0) return;
        Long usedBySchedules = scheduleMapper.selectCount(
                new LambdaQueryWrapper<Schedule>()
                        .eq(Schedule::getWorkspaceId, workspaceId));
        if (usedBySchedules != null && usedBySchedules > 0) {
            log.info("空间下已无会话，但仍被自动化任务引用，保留空间登记 workspaceId={} 任务数={}",
                    workspaceId, usedBySchedules);
            return;
        }
        workspaceMapper.deleteById(workspaceId);
        log.info("空间下最后一条会话已删除，一并移除空间登记 workspaceId={} userId={}（磁盘目录未做任何改动）",
                workspaceId, userId);
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

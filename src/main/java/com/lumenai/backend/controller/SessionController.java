package com.lumenai.backend.controller;

import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.common.response.R;
import com.lumenai.backend.dto.SessionCreateRequest;
import com.lumenai.backend.dto.SessionResponse;
import com.lumenai.backend.dto.SessionUpdateRequest;
import com.lumenai.backend.entity.Message;
import com.lumenai.backend.entity.Session;
import com.lumenai.backend.mapper.MessageMapper;
import com.lumenai.backend.service.SessionService;
import com.lumenai.backend.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final WorkspaceService workspaceService;
    private final MessageMapper messageMapper;

    /** 列出当前用户的会话 */
    @GetMapping
    public R<List<SessionResponse>> list() {
        return R.ok(sessionService.listByUser(UserContext.get()));
    }

    /** 创建会话（带工作空间绑定；需 X-Device-Id 判定该空间是否在本机） */
    @PostMapping
    public R<SessionResponse> create(@RequestBody(required = false) SessionCreateRequest req,
                                     @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        if (req == null) req = new SessionCreateRequest();
        Session s = sessionService.create(UserContext.get(), req, deviceId);
        return R.ok(SessionResponse.from(s, null, false, workspaceNameOf(s, deviceId)));
    }

    /** 会话绑定的空间名（空间事后被移除时返回 null，仅影响展示，不影响会话可用性） */
    private String workspaceNameOf(Session s, String deviceId) {
        if (s.getWorkspaceId() == null) return null;
        try {
            com.lumenai.backend.entity.Workspace ws =
                    workspaceService.resolveForDevice(s.getUserId(), s.getWorkspaceId(), deviceId);
            return ws == null ? null : ws.getName();
        } catch (Exception e) {
            return null;
        }
    }

    /** 获取会话详情 */
    @GetMapping("/{id}")
    public R<SessionResponse> get(@PathVariable Long id) {
        Session s = sessionService.getOrThrow(UserContext.get(), id);
        return R.ok(SessionResponse.from(s, null, false));
    }

    /** 更新会话 */
    @PatchMapping("/{id}")
    public R<SessionResponse> update(@PathVariable Long id, @RequestBody SessionUpdateRequest req) {
        Session s = sessionService.update(UserContext.get(), id, req);
        return R.ok(SessionResponse.from(s, null, false));
    }

    /** 删除会话 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        sessionService.delete(UserContext.get(), id);
        return R.ok();
    }

    /** 获取会话内消息历史 */
    @GetMapping("/{id}/messages")
    public R<List<Message>> messages(@PathVariable Long id) {
        return R.ok(sessionService.listMessages(UserContext.get(), id));
    }

    /** 删除单条消息 */
    @DeleteMapping("/{sessionId}/messages/{messageId}")
    public R<Void> deleteMessage(@PathVariable Long sessionId, @PathVariable Long messageId) {
        // 验证 session 属于当前用户
        sessionService.getOrThrow(UserContext.get(), sessionId);
        // 验证 message 存在且属于该 session
        Message m = messageMapper.selectById(messageId);
        if (m == null || !m.getSessionId().equals(sessionId)) {
            return R.fail(404, "消息不存在");
        }
        messageMapper.deleteById(messageId);
        return R.ok();
    }
}

package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumenai.backend.common.exception.BusinessException;
import com.lumenai.backend.common.exception.ErrorCode;
import com.lumenai.backend.dto.WorkspaceCreateRequest;
import com.lumenai.backend.dto.WorkspaceListVO;
import com.lumenai.backend.dto.WorkspaceUpdateRequest;
import com.lumenai.backend.dto.WorkspaceUsageVO;
import com.lumenai.backend.dto.WorkspaceVO;
import com.lumenai.backend.entity.Workspace;
import com.lumenai.backend.mapper.WorkspaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 工作空间元数据 CRUD。
 *
 * <p>🔴 本类<b>不接触任何路径</b>，也不该接触 —— 产品部署在云服务器上，本机路径对服务端无意义。
 * 服务端只回答「这个账号在这台设备上有哪些项目、叫什么名字」。</p>
 *
 * <p>🔴 也<b>不持有权限档位</b>：档位是账号级全局设置（{@code users.permission_mode}），
 * 空间级覆盖（原 {@code workspaces.permission_mode} 列）已随 2026-09-22 重构删除 ——
 * 档位只有一处真源，就不会出现「同一台机器上两个空间行为不一致却查不出原因」。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    /** 名称长度上限（与表定义一致，超长直接拒，不靠数据库报错兜底） */
    private static final int MAX_NAME_LENGTH = 64;

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceQuotaService quotaService;

    /** 当前设备的空间列表（`last_used_at` 倒序，nulls last）+ 配额用量 */
    public WorkspaceListVO list(Long userId, String deviceId) {
        String device = requireDevice(deviceId);
        List<Workspace> rows = workspaceMapper.selectList(
                new LambdaQueryWrapper<Workspace>()
                        .eq(Workspace::getUserId, userId)
                        .eq(Workspace::getDeviceId, device)
                        // 从未使用过的排最后；MySQL 默认 NULL 最小，所以 DESC 时 NULL 会落到末尾
                        .orderByDesc(Workspace::getLastUsedAt)
                        .orderByDesc(Workspace::getId));
        List<WorkspaceVO> items = rows.stream().map(WorkspaceVO::from).collect(Collectors.toList());
        return new WorkspaceListVO(items, quotaService.usedCount(userId), quotaService.workspaceLimit(userId));
    }

    /** 配额用量（设置页单独查询；跨设备合计） */
    public WorkspaceUsageVO usage(Long userId) {
        return new WorkspaceUsageVO(quotaService.usedCount(userId), quotaService.workspaceLimit(userId));
    }

    /**
     * 登记空间元数据（幂等：同设备同名可直接复用，见 {@code reuseIfExists}）。
     *
     * <p>{@code reuseIfExists=true} 时同名冲突返回既有的那一行而不是报错 ——
     * 「打开本地文件夹」与自动化任务的名字登记都可能撞上已经被登记过的名字
     * （客户端重装后本地映射丢失，就是典型场景）；而「新建工作空间」传 false，
     * 重名要明确报错，否则用户会以为新建了目录、实际复用了另一个同名空间。</p>
     */
    @Transactional
    public WorkspaceVO create(Long userId, WorkspaceCreateRequest req, String headerDeviceId, boolean reuseIfExists) {
        String device = requireDevice(firstNonBlank(req == null ? null : req.getDeviceId(), headerDeviceId));
        String name = normalizeName(req == null ? null : req.getName());

        Workspace existing = findByName(userId, device, name);
        if (existing != null) {
            if (reuseIfExists) {
                log.info("工作空间已存在，复用 id={} userId={} device={} name={}",
                        existing.getId(), userId, device, name);
                return WorkspaceVO.from(existing);
            }
            throw new BusinessException(409, ErrorCode.WORKSPACE_NAME_TAKEN, "已存在同名工作空间：" + name);
        }

        if (!quotaService.canCreate(userId)) {
            int limit = quotaService.workspaceLimit(userId);
            throw new BusinessException(403, ErrorCode.WORKSPACE_LIMIT_EXCEEDED,
                    "已达当前套餐的工作空间上限（" + limit + " 个），请升级套餐后重试");
        }

        LocalDateTime now = LocalDateTime.now();
        Workspace w = new Workspace();
        w.setUserId(userId);
        w.setDeviceId(device);
        w.setName(name);
        // 登记即可用：视为刚使用过，列表里排在前面
        w.setLastUsedAt(now);
        w.setCreatedAt(now);
        w.setUpdatedAt(now);
        workspaceMapper.insert(w);
        log.info("工作空间已登记 id={} userId={} device={} name={}", w.getId(), userId, device, name);
        return WorkspaceVO.from(w);
    }

    /** 重命名（只改元数据，不碰磁盘目录；权限档位是账号级设置，不在这里改） */
    @Transactional
    public WorkspaceVO update(Long userId, Long workspaceId, WorkspaceUpdateRequest req) {
        Workspace w = getOrThrow(userId, workspaceId);
        boolean changed = false;

        if (req != null && req.getName() != null) {
            String name = normalizeName(req.getName());
            if (!name.equals(w.getName())) {
                Workspace dup = findByName(userId, w.getDeviceId(), name);
                if (dup != null && !dup.getId().equals(w.getId())) {
                    throw new BusinessException(409, ErrorCode.WORKSPACE_NAME_TAKEN, "已存在同名工作空间：" + name);
                }
                w.setName(name);
                changed = true;
            }
        }

        if (changed) {
            w.setUpdatedAt(LocalDateTime.now());
            workspaceMapper.updateById(w);
            log.info("工作空间已更新 id={} name={}", w.getId(), w.getName());
        }
        return WorkspaceVO.from(w);
    }

    /** 触碰最近使用时间（客户端选中该空间 / 打开会话时调用） */
    @Transactional
    public void touch(Long userId, Long workspaceId) {
        Workspace w = getOrThrow(userId, workspaceId);
        w.setLastUsedAt(LocalDateTime.now());
        w.setUpdatedAt(LocalDateTime.now());
        workspaceMapper.updateById(w);
    }

    /**
     * 移除登记：<b>只删这一行</b>。
     *
     * <p>文件系统零操作 —— 磁盘上的目录属于用户，服务端既碰不到也不该碰。
     * <b>不级联删会话</b>（会话是对话记录，与登记无关）。</p>
     */
    @Transactional
    public void delete(Long userId, Long workspaceId) {
        Workspace w = getOrThrow(userId, workspaceId);
        workspaceMapper.deleteById(w.getId());
        log.info("工作空间登记已移除 id={} userId={}（磁盘目录未做任何改动）", w.getId(), userId);
    }

    /** 取单个（校验归属） */
    public Workspace getOrThrow(Long userId, Long workspaceId) {
        if (workspaceId == null) {
            throw new BusinessException(404, ErrorCode.WORKSPACE_NOT_FOUND, "工作空间不存在");
        }
        Workspace w = workspaceMapper.selectById(workspaceId);
        if (w == null) {
            throw new BusinessException(404, ErrorCode.WORKSPACE_NOT_FOUND, "工作空间不存在或已被移除");
        }
        if (!w.getUserId().equals(userId)) {
            throw new BusinessException(403, ErrorCode.WORKSPACE_NOT_FOUND, "无权访问该工作空间");
        }
        return w;
    }

    /**
     * 宽松取单个（不抛异常）。
     *
     * <p>给后台链路用：{@code ScheduleRunner} 在无人值守的调度线程里执行任务，
     * 绑定的空间可能已被用户移除 —— 那种情况该「按无空间继续跑」，而不是抛异常把
     * 整个任务标记失败。HTTP 入口仍然用 {@link #getOrThrow}。</p>
     *
     * @return 命中返回实体；{@code workspaceId} 为空 / 不存在 / 不属于该用户均返回 {@code null}
     */
    public Workspace find(Long userId, Long workspaceId) {
        if (userId == null || workspaceId == null) return null;
        Workspace w = workspaceMapper.selectById(workspaceId);
        if (w == null || !w.getUserId().equals(userId)) return null;
        return w;
    }

    /**
     * 会话执行上下文用：解析会话绑定的空间，**必须是当前设备上的**。
     *
     * @return 命中返回实体；未绑定 / 跨设备 / 已被移除均返回 {@code null}
     *         （调用方据此剔除本地类工具，而不是抛异常打断聊天）
     */
    public Workspace resolveForDevice(Long userId, Long workspaceId, String deviceId) {
        if (workspaceId == null) return null;
        Workspace w = workspaceMapper.selectById(workspaceId);
        if (w == null || !w.getUserId().equals(userId)) {
            log.warn("会话绑定的工作空间不存在，按无空间处理 workspaceId={} userId={}", workspaceId, userId);
            return null;
        }
        if (deviceId == null || deviceId.isBlank() || !deviceId.equals(w.getDeviceId())) {
            log.info("工作空间不在当前设备，本地工具不下发 workspaceId={} 归属设备={} 当前设备={}",
                    workspaceId, w.getDeviceId(), deviceId);
            return null;
        }
        return w;
    }

    private Workspace findByName(Long userId, String device, String name) {
        return workspaceMapper.selectOne(
                new LambdaQueryWrapper<Workspace>()
                        .eq(Workspace::getUserId, userId)
                        .eq(Workspace::getDeviceId, device)
                        .eq(Workspace::getName, name)
                        .last("LIMIT 1"));
    }

    private String requireDevice(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            // 设备维度是这个表的主键组成部分，缺了就没法回答「这是哪台机器的空间」
            throw new BusinessException(400, "缺少设备标识（X-Device-Id）");
        }
        return deviceId.trim();
    }

    private String normalizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new BusinessException(400, "工作空间名称不能为空");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(400, "工作空间名称过长（最多 " + MAX_NAME_LENGTH + " 个字符）");
        }
        return name;
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }
}

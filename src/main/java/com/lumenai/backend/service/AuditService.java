package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumenai.backend.common.exception.BusinessException;
import com.lumenai.backend.dto.AuditEventRequest;
import com.lumenai.backend.dto.AuditListVO;
import com.lumenai.backend.entity.AuditLog;
import com.lumenai.backend.entity.Workspace;
import com.lumenai.backend.mapper.AuditLogMapper;
import com.lumenai.backend.mapper.WorkspaceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * 审计中心（P4）：沙箱决定的上报落库 + 查询 + 导出。
 *
 * <p>判定在客户端，服务端只做三件事：<b>归属</b>（userId 一律取 JWT 主体、
 * workspaceId 必须属于本人）、<b>校验</b>（枚举值非法显式拒绝，红线 19）、<b>存储</b>。
 * {@code targetDigest} 只收摘要 —— 请求体里出现路径/命令原文无处可存，也不该有。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final Set<String> TYPES = Set.of("file", "command", "network");
    private static final Set<String> DECISIONS = Set.of("allow", "deny");
    /** P5 隔离层级：客户端如实上报；空值按 policy-only 落库（缺上报通道的旧客户端即此层级） */
    private static final Set<String> ISOLATIONS = Set.of("os-fence", "policy-only");
    private static final int MAX_EXPORT_ROWS = 50000;

    private final AuditLogMapper auditLogMapper;
    private final WorkspaceMapper workspaceMapper;

    /** 客户端上报一条审计事件（工具执行通道的每次沙箱决定） */
    public void report(Long userId, String deviceId, AuditEventRequest req) {
        if (req == null || req.getType() == null || req.getToolName() == null
                || req.getTargetDigest() == null || req.getDecision() == null) {
            throw new BusinessException(400, "审计事件缺少必填字段（type/toolName/targetDigest/decision）");
        }
        if (!TYPES.contains(req.getType())) {
            throw new BusinessException(400, "非法的审计事件类型：" + req.getType());
        }
        if (!DECISIONS.contains(req.getDecision())) {
            throw new BusinessException(400, "非法的审计决定：" + req.getDecision());
        }
        if (req.getIsolation() != null && !ISOLATIONS.contains(req.getIsolation())) {
            // 隔离层级非法值显式拒绝（红线 19）：落一个假层级比缺层级更糟
            throw new BusinessException(400, "非法的隔离层级：" + req.getIsolation());
        }
        String digest = req.getTargetDigest().trim();
        // 摘要必须是 16 位 hex：这是「原文没进服务端」的机器可验证据（红线 18 同源）
        if (!digest.matches("[0-9a-fA-F]{16}")) {
            throw new BusinessException(400, "targetDigest 必须是 16 位十六进制摘要，不接收原文");
        }
        if (req.getWorkspaceId() != null) {
            Workspace ws = workspaceMapper.selectById(req.getWorkspaceId());
            if (ws == null || !ws.getUserId().equals(userId)) {
                // 伪造他人空间的审计记录：显式拒绝，不静默改写归属
                throw new BusinessException(404, "工作空间不存在或不属于当前用户");
            }
        }

        AuditLog logRow = new AuditLog();
        logRow.setUserId(userId);
        logRow.setDeviceId(deviceId);
        logRow.setSessionId(req.getSessionId());
        logRow.setWorkspaceId(req.getWorkspaceId());
        logRow.setType(req.getType());
        logRow.setToolName(req.getToolName());
        logRow.setTargetDigest(digest.toLowerCase());
        logRow.setDecision(req.getDecision());
        logRow.setReason(trimReason(req.getReason()));
        logRow.setIsolation(req.getIsolation() == null ? "policy-only" : req.getIsolation());
        auditLogMapper.insert(logRow);
    }

    /** 分页查询（时间倒序），type / decision 可选过滤；只看自己的记录 */
    public AuditListVO list(Long userId, String type, String decision, int page, int size) {
        if (type != null && !type.isBlank() && !TYPES.contains(type)) {
            throw new BusinessException(400, "非法的审计事件类型：" + type);
        }
        if (decision != null && !decision.isBlank() && !DECISIONS.contains(decision)) {
            throw new BusinessException(400, "非法的审计决定：" + decision);
        }
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 200);

        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<AuditLog>().eq(AuditLog::getUserId, userId);
        if (type != null && !type.isBlank()) qw.eq(AuditLog::getType, type);
        if (decision != null && !decision.isBlank()) qw.eq(AuditLog::getDecision, decision);
        qw.orderByDesc(AuditLog::getId);

        Page<AuditLog> result = auditLogMapper.selectPage(new Page<>(p, s), qw);
        return AuditListVO.of(result.getTotal(), p, s, result.getRecords());
    }

    /**
     * 导出 CSV（当前过滤条件下的全部记录，上限 5 万行）。
     *
     * <p>表头与数据都带 BOM 兼容：Excel 直接打开中文不乱码。
     * 单元格统一做 CSV 转义（引号/逗号/换行），reason 是自由文本。</p>
     */
    public String exportCsv(Long userId, String type, String decision) {
        if (type != null && !type.isBlank() && !TYPES.contains(type)) {
            throw new BusinessException(400, "非法的审计事件类型：" + type);
        }
        if (decision != null && !decision.isBlank() && !DECISIONS.contains(decision)) {
            throw new BusinessException(400, "非法的审计决定：" + decision);
        }
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<AuditLog>().eq(AuditLog::getUserId, userId);
        if (type != null && !type.isBlank()) qw.eq(AuditLog::getType, type);
        if (decision != null && !decision.isBlank()) qw.eq(AuditLog::getDecision, decision);
        qw.orderByDesc(AuditLog::getId).last("LIMIT " + MAX_EXPORT_ROWS);

        List<AuditLog> rows = auditLogMapper.selectList(qw);
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("时间,类型,工具,决定,目标摘要,原因,隔离层级,设备,空间ID,会话ID\n");
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (AuditLog r : rows) {
            sb.append(csvCell(r.getCreatedAt() == null ? "" : r.getCreatedAt().format(fmt))).append(',')
                    .append(csvCell(r.getType())).append(',')
                    .append(csvCell(r.getToolName())).append(',')
                    .append(csvCell(r.getDecision())).append(',')
                    .append(csvCell(r.getTargetDigest())).append(',')
                    .append(csvCell(r.getReason())).append(',')
                    .append(csvCell(r.getIsolation() == null ? "policy-only" : r.getIsolation())).append(',')
                    .append(csvCell(r.getDeviceId())).append(',')
                    .append(r.getWorkspaceId() == null ? "" : r.getWorkspaceId()).append(',')
                    .append(r.getSessionId() == null ? "" : r.getSessionId()).append('\n');
        }
        return sb.toString();
    }

    /** reason 是自由文本，超长截断（列宽 512），绝不因一条超长原因让上报失败 */
    private String trimReason(String reason) {
        if (reason == null) return null;
        String t = reason.trim();
        return t.length() > 500 ? t.substring(0, 500) : t;
    }

    private String csvCell(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}

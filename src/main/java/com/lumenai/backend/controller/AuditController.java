package com.lumenai.backend.controller;

import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.common.response.R;
import com.lumenai.backend.dto.AuditEventRequest;
import com.lumenai.backend.dto.AuditListVO;
import com.lumenai.backend.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计中心接口（P4，薄控制器：取上下文 + 转发 + 包 {@code R<T>}）。
 *
 * <p>上报（POST /events）由 Electron 主进程直调（JWT 放 header，与 tool-result 同通道风格）；
 * 列表与导出由渲染进程设置页调用。导出返回原始 CSV（带 BOM），不走 R&lt;T&gt; 包装。</p>
 */
@Slf4j
@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    /** 客户端上报一条沙箱审计事件（拦截 / 放行 / 审批决定） */
    @PostMapping("/events")
    public R<Void> report(@RequestBody AuditEventRequest req,
                          @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        auditService.report(UserContext.get(), deviceId, req);
        return R.ok();
    }

    /** 分页查询（时间倒序，type / decision 可选过滤） */
    @GetMapping("/list")
    public R<AuditListVO> list(@RequestParam(value = "type", required = false) String type,
                               @RequestParam(value = "decision", required = false) String decision,
                               @RequestParam(value = "page", required = false, defaultValue = "1") int page,
                               @RequestParam(value = "size", required = false, defaultValue = "50") int size) {
        return R.ok(auditService.list(UserContext.get(), type, decision, page, size));
    }

    /** 导出 CSV（当前过滤条件下全部记录，带 BOM，Excel 直开不乱码） */
    @GetMapping(value = "/export", produces = "text/csv;charset=UTF-8")
    public String export(@RequestParam(value = "type", required = false) String type,
                         @RequestParam(value = "decision", required = false) String decision) {
        return auditService.exportCsv(UserContext.get(), type, decision);
    }
}

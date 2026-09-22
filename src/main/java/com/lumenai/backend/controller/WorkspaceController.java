package com.lumenai.backend.controller;

import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.common.response.R;
import com.lumenai.backend.dto.WorkspaceCreateRequest;
import com.lumenai.backend.dto.WorkspaceListVO;
import com.lumenai.backend.dto.WorkspaceUpdateRequest;
import com.lumenai.backend.dto.WorkspaceUsageVO;
import com.lumenai.backend.dto.WorkspaceVO;
import com.lumenai.backend.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作空间接口（薄控制器：取上下文 + 转发 + 包 {@code R<T>}）。
 *
 * <p>所有响应都<b>不含路径</b> —— 路径只在客户端本地（需求文档 §5.4）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/workspace")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    /** 当前**设备**的空间列表（最近使用倒序）+ 配额用量 */
    @GetMapping("/list")
    public R<WorkspaceListVO> list(@RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        return R.ok(workspaceService.list(UserContext.get(), deviceId));
    }

    /** 配额用量（设置页） */
    @GetMapping("/usage")
    public R<WorkspaceUsageVO> usage() {
        return R.ok(workspaceService.usage(UserContext.get()));
    }

    /**
     * 登记空间元数据。
     *
     * @param reuse 同名时是否复用既有行（「打开本地文件夹」「迁移历史工作目录」传 true；
     *              「新建工作空间」不传，重名要明确报错）
     */
    @PostMapping
    public R<WorkspaceVO> create(@RequestBody WorkspaceCreateRequest req,
                                 @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                                 @RequestParam(value = "reuse", required = false, defaultValue = "false") boolean reuse) {
        return R.ok(workspaceService.create(UserContext.get(), req, deviceId, reuse));
    }

    /** 重命名 / 改权限档位（只改元数据） */
    @PutMapping("/{id}")
    public R<WorkspaceVO> update(@PathVariable Long id, @RequestBody WorkspaceUpdateRequest req) {
        return R.ok(workspaceService.update(UserContext.get(), id, req));
    }

    /** 触碰最近使用时间 */
    @PutMapping("/{id}/used")
    public R<Void> touch(@PathVariable Long id) {
        workspaceService.touch(UserContext.get(), id);
        return R.ok();
    }

    /** 移除登记（只删记录，磁盘目录不动） */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        workspaceService.delete(UserContext.get(), id);
        return R.ok();
    }
}

package com.lumenai.backend.controller;

import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.common.response.R;
import com.lumenai.backend.dto.ScheduleCreateRequest;
import com.lumenai.backend.dto.ScheduleResponse;
import com.lumenai.backend.dto.ScheduleUpdateRequest;
import com.lumenai.backend.entity.Schedule;
import com.lumenai.backend.entity.ScheduleRun;
import com.lumenai.backend.service.ScheduleRunService;
import com.lumenai.backend.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final ScheduleRunService scheduleRunService;

    /** 列出当前用户的自动化任务 */
    @GetMapping
    public R<List<ScheduleResponse>> list() {
        return R.ok(scheduleService.listByUser(UserContext.get()));
    }

    /** 创建自动化任务 */
    @PostMapping
    public R<ScheduleResponse> create(@RequestBody ScheduleCreateRequest req) {
        Schedule s = scheduleService.create(UserContext.get(), req);
        return R.ok(ScheduleResponse.from(s));
    }

    /** 获取单个任务 */
    @GetMapping("/{id}")
    public R<ScheduleResponse> get(@PathVariable Long id) {
        Schedule s = scheduleService.getOrThrow(UserContext.get(), id);
        return R.ok(ScheduleResponse.from(s));
    }

    /** 更新任务 */
    @PatchMapping("/{id}")
    public R<ScheduleResponse> update(@PathVariable Long id, @RequestBody ScheduleUpdateRequest req) {
        Schedule s = scheduleService.update(UserContext.get(), id, req);
        return R.ok(ScheduleResponse.from(s));
    }

    /** 删除任务 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        scheduleService.delete(UserContext.get(), id);
        return R.ok();
    }

    /** 切换 active / paused */
    @PostMapping("/{id}/toggle")
    public R<ScheduleResponse> toggle(@PathVariable Long id) {
        Schedule s = scheduleService.toggleStatus(UserContext.get(), id);
        return R.ok(ScheduleResponse.from(s));
    }

    /** 手动立即执行：创建一条 running 状态的执行记录，由聊天完成回调更新状态 */
    @PostMapping("/{id}/run")
    public R<Map<String, Object>> runNow(@PathVariable Long id) {
        Long userId = UserContext.get();
        Schedule s = scheduleService.getOrThrow(userId, id);
        ScheduleRun run = scheduleRunService.startRun(s.getId(), userId, "manual");
        Map<String, Object> result = new HashMap<>();
        result.put("runId", run.getId());
        result.put("scheduleId", s.getId());
        result.put("scheduleName", s.getName());
        result.put("message", s.getMessage());
        return R.ok(result);
    }
}

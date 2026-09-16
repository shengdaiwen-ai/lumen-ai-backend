package com.lumenai.backend.controller;

import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.common.response.R;
import com.lumenai.backend.dto.ScheduleRunResponse;
import com.lumenai.backend.entity.Schedule;
import com.lumenai.backend.entity.ScheduleRun;
import com.lumenai.backend.mapper.ScheduleMapper;
import com.lumenai.backend.service.ScheduleRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 自动化任务执行历史接口
 */
@Slf4j
@RestController
@RequestMapping("/schedule-runs")
@RequiredArgsConstructor
public class ScheduleRunController {

    private final ScheduleRunService scheduleRunService;
    private final ScheduleMapper scheduleMapper;

    /** 查询当前用户的执行历史（时间范围必填） */
    @GetMapping
    public R<List<ScheduleRunResponse>> list(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end,
            @RequestParam(required = false) String triggerType
    ) {
        Long userId = UserContext.get();
        LocalDateTime startDt = start.atStartOfDay();
        LocalDateTime endDt = end.atTime(23, 59, 59);

        List<ScheduleRun> runs = scheduleRunService.listByUser(userId, startDt, endDt, triggerType);

        // 批量加载 schedule 名称
        List<Long> scheduleIds = runs.stream()
                .map(ScheduleRun::getScheduleId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Schedule> scheduleMap = new HashMap<>();
        if (!scheduleIds.isEmpty()) {
            List<Schedule> schedules = scheduleMapper.selectBatchIds(scheduleIds);
            for (Schedule s : schedules) {
                scheduleMap.put(s.getId(), s);
            }
        }

        List<ScheduleRunResponse> resp = runs.stream()
                .map(run -> ScheduleRunResponse.from(run, scheduleMap.get(run.getScheduleId())))
                .collect(Collectors.toList());

        return R.ok(resp);
    }
}

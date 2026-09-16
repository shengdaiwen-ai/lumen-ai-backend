package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumenai.backend.common.exception.BusinessException;
import com.lumenai.backend.entity.ScheduleRun;
import com.lumenai.backend.mapper.ScheduleRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动化任务执行历史 Service
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleRunService {

    private final ScheduleRunMapper scheduleRunMapper;

    /** 新建一条执行记录，状态为 running */
    public ScheduleRun startRun(Long scheduleId, Long userId, String triggerType) {
        ScheduleRun run = new ScheduleRun();
        run.setScheduleId(scheduleId);
        run.setUserId(userId);
        run.setStatus("running");
        run.setTriggerType(triggerType);
        run.setStartedAt(LocalDateTime.now());
        scheduleRunMapper.insert(run);
        log.info("Schedule run started: id={}, scheduleId={}, trigger={}", run.getId(), scheduleId, triggerType);
        return run;
    }

    /** 完成执行记录：success / failed */
    public void completeRun(Long runId, String status, String result, String error) {
        ScheduleRun run = scheduleRunMapper.selectById(runId);
        if (run == null) return;
        run.setStatus(status);
        run.setResult(result);
        run.setError(error);
        run.setEndedAt(LocalDateTime.now());
        scheduleRunMapper.updateById(run);
        log.info("Schedule run completed: id={}, status={}", runId, status);
    }

    /** 更新执行记录关联的会话ID */
    public void updateSessionId(Long runId, Long sessionId) {
        ScheduleRun run = scheduleRunMapper.selectById(runId);
        if (run == null) return;
        run.setSessionId(sessionId);
        scheduleRunMapper.updateById(run);
    }

    /** 查询当前用户的执行历史 */
    public List<ScheduleRun> listByUser(Long userId, LocalDateTime start, LocalDateTime end, String triggerType) {
        LambdaQueryWrapper<ScheduleRun> qw = new LambdaQueryWrapper<ScheduleRun>()
                .eq(ScheduleRun::getUserId, userId)
                .orderByDesc(ScheduleRun::getStartedAt);
        if (start != null) qw.ge(ScheduleRun::getStartedAt, start);
        if (end != null) qw.le(ScheduleRun::getStartedAt, end);
        if (triggerType != null && !"all".equals(triggerType)) {
            qw.eq(ScheduleRun::getTriggerType, triggerType);
        }
        return scheduleRunMapper.selectList(qw);
    }

    public ScheduleRun getOrThrow(Long id) {
        ScheduleRun run = scheduleRunMapper.selectById(id);
        if (run == null) throw new BusinessException("执行记录不存在");
        return run;
    }
}

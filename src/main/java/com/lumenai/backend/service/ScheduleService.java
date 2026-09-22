package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumenai.backend.common.exception.BusinessException;
import com.lumenai.backend.dto.ScheduleCreateRequest;
import com.lumenai.backend.dto.ScheduleResponse;
import com.lumenai.backend.dto.ScheduleUpdateRequest;
import com.lumenai.backend.entity.Schedule;
import com.lumenai.backend.mapper.ScheduleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleMapper scheduleMapper;
    private final WorkspaceService workspaceService;

    public List<ScheduleResponse> listByUser(Long userId) {
        List<Schedule> list = scheduleMapper.selectList(
                new LambdaQueryWrapper<Schedule>()
                        .eq(Schedule::getUserId, userId)
                        .orderByDesc(Schedule::getUpdatedAt)
        );
        return list.stream().map(ScheduleResponse::from).collect(Collectors.toList());
    }

    public Schedule create(Long userId, ScheduleCreateRequest req) {
        Schedule s = new Schedule();
        s.setUserId(userId);
        s.setName(req.getName() != null ? req.getName() : "新自动化任务");
        // 绑定的空间只接受「自己名下」的（越权 id 直接 404）。这里<b>不</b>校验设备：
        // 任务的执行设备由空间自身决定（ScheduleRunner 用 ws.deviceId 建会话），
        // 与「在哪台机器上点了创建」无关 —— 用户在 A 机建任务、空间属于 A 机是常态，
        // 但接口不该把这条当硬约束（否则共享/迁移场景全被堵死）。
        if (req.getWorkspaceId() != null) {
            s.setWorkspaceId(workspaceService.getOrThrow(userId, req.getWorkspaceId()).getId());
        }
        s.setFrequency(req.getFrequency());
        s.setWeekday(req.getWeekday());
        s.setMonthDay(req.getMonthDay());
        s.setIntervalValue(req.getIntervalValue());
        s.setIntervalUnit(req.getIntervalUnit());
        s.setRunHour(req.getRunHour());
        s.setRunMinute(req.getRunMinute());
        s.setMessage(req.getMessage());
        s.setCron(req.getCron());
        s.setTimezone(req.getTimezone() != null ? req.getTimezone() : "Asia/Shanghai");
        s.setStatus("active");
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        s.setNextRunAt(computeNextRun(s, LocalDateTime.now()));
        scheduleMapper.insert(s);
        return s;
    }

    public Schedule getOrThrow(Long userId, Long id) {
        Schedule s = scheduleMapper.selectById(id);
        if (s == null) throw new BusinessException("任务不存在");
        if (!s.getUserId().equals(userId)) throw new BusinessException("无权访问此任务");
        return s;
    }

    public Schedule update(Long userId, Long id, ScheduleUpdateRequest req) {
        Schedule s = getOrThrow(userId, id);
        if (req.getName() != null) s.setName(req.getName());
        if (req.getStatus() != null) s.setStatus(req.getStatus());
        if (req.getMessage() != null) s.setMessage(req.getMessage());
        if (req.getFrequency() != null) s.setFrequency(req.getFrequency());
        if (req.getWeekday() != null) s.setWeekday(req.getWeekday());
        if (req.getMonthDay() != null) s.setMonthDay(req.getMonthDay());
        if (req.getIntervalValue() != null) s.setIntervalValue(req.getIntervalValue());
        if (req.getIntervalUnit() != null) s.setIntervalUnit(req.getIntervalUnit());
        if (req.getRunHour() != null) s.setRunHour(req.getRunHour());
        if (req.getRunMinute() != null) s.setRunMinute(req.getRunMinute());
        s.setUpdatedAt(LocalDateTime.now());
        scheduleMapper.updateById(s);
        return s;
    }

    public void delete(Long userId, Long id) {
        getOrThrow(userId, id);
        scheduleMapper.deleteById(id);
    }

    public Schedule toggleStatus(Long userId, Long id) {
        Schedule s = getOrThrow(userId, id);
        s.setStatus("active".equals(s.getStatus()) ? "paused" : "active");
        s.setUpdatedAt(LocalDateTime.now());
        scheduleMapper.updateById(s);
        return s;
    }

    /** 根据频率计算下次执行时间 */
    public static LocalDateTime computeNextRun(Schedule s, LocalDateTime from) {
        int hour = s.getRunHour() != null ? s.getRunHour() : 9;
        int minute = s.getRunMinute() != null ? s.getRunMinute() : 0;

        switch (s.getFrequency() == null ? "每天" : s.getFrequency()) {
            case "每天": {
                LocalDateTime next = from.toLocalDate().atTime(hour, minute);
                return next.isAfter(from) ? next : next.plusDays(1);
            }
            case "每周": {
                String[] days = (s.getWeekday() != null ? s.getWeekday() : "1").split(",");
                LocalDateTime base = from.toLocalDate().atTime(hour, minute);
                if (base.isBefore(from)) base = base.plusDays(1);
                for (int i = 0; i < 8; i++) {
                    LocalDateTime candidate = base.plusDays(i);
                    int dow = candidate.getDayOfWeek().getValue();
                    for (String d : days) {
                        if (Integer.parseInt(d.trim()) == dow) return candidate;
                    }
                }
                return base.plusDays(7);
            }
            case "每月": {
                int day = s.getMonthDay() != null ? s.getMonthDay() : 1;
                LocalDateTime thisMonth = from.withDayOfMonth(Math.min(day, from.toLocalDate().lengthOfMonth()))
                        .withHour(hour).withMinute(minute).withSecond(0);
                if (thisMonth.isAfter(from)) return thisMonth;
                LocalDateTime nextMonth = from.withDayOfMonth(1).withHour(hour).withMinute(minute).withSecond(0).plusMonths(1);
                return nextMonth.withDayOfMonth(Math.min(day, nextMonth.toLocalDate().lengthOfMonth()));
            }
            case "间隔触发": {
                int val = s.getIntervalValue() != null ? s.getIntervalValue() : 1;
                String unit = s.getIntervalUnit() != null ? s.getIntervalUnit() : "小时";
                switch (unit) {
                    case "分钟": return from.plusMinutes(val);
                    case "小时": return from.plusHours(val);
                    case "天": return from.toLocalDate().plusDays(val).atTime(hour, minute);
                    default: return from.plusHours(val);
                }
            }
            default:
                return from.plusDays(1).withHour(hour).withMinute(minute);
        }
    }
}

package com.lumenai.backend.dto;

import com.lumenai.backend.entity.Schedule;
import com.lumenai.backend.entity.ScheduleRun;

import java.time.LocalDateTime;

/**
 * 执行历史响应，附带任务名称
 */
public class ScheduleRunResponse {

    private Long id;
    private Long scheduleId;
    private String scheduleName;
    private Long sessionId;
    private String status;       // running / success / failed
    private String triggerType;  // manual / auto
    private String result;
    private String error;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;

    public static ScheduleRunResponse from(ScheduleRun run, Schedule schedule) {
        ScheduleRunResponse r = new ScheduleRunResponse();
        r.id = run.getId();
        r.scheduleId = run.getScheduleId();
        r.scheduleName = schedule != null ? schedule.getName() : null;
        r.sessionId = run.getSessionId();
        r.status = run.getStatus();
        r.triggerType = run.getTriggerType();
        r.result = run.getResult();
        r.error = run.getError();
        r.startedAt = run.getStartedAt();
        r.endedAt = run.getEndedAt();
        return r;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getScheduleId() { return scheduleId; }
    public void setScheduleId(Long scheduleId) { this.scheduleId = scheduleId; }

    public String getScheduleName() { return scheduleName; }
    public void setScheduleName(String scheduleName) { this.scheduleName = scheduleName; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
}

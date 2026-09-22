package com.lumenai.backend.dto;

import com.lumenai.backend.entity.Schedule;

import java.time.LocalDateTime;

public class ScheduleResponse {

    private Long id;
    private String name;
    /** 绑定的工作空间 id；null = 无空间（任务运行时本地类工具不下发） */
    private Long workspaceId;
    private String frequency;
    private String weekday;
    private Integer monthDay;
    private Integer intervalValue;
    private String intervalUnit;
    private Integer runHour;
    private Integer runMinute;
    private String message;
    private String cron;
    private String timezone;
    private String status;
    private LocalDateTime lastRunAt;
    private LocalDateTime nextRunAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ScheduleResponse from(Schedule s) {
        ScheduleResponse r = new ScheduleResponse();
        r.id = s.getId();
        r.name = s.getName();
        r.workspaceId = s.getWorkspaceId();
        r.frequency = s.getFrequency();
        r.weekday = s.getWeekday();
        r.monthDay = s.getMonthDay();
        r.intervalValue = s.getIntervalValue();
        r.intervalUnit = s.getIntervalUnit();
        r.runHour = s.getRunHour();
        r.runMinute = s.getRunMinute();
        r.message = s.getMessage();
        r.cron = s.getCron();
        r.timezone = s.getTimezone();
        r.status = s.getStatus();
        r.lastRunAt = s.getLastRunAt();
        r.nextRunAt = s.getNextRunAt();
        r.createdAt = s.getCreatedAt();
        r.updatedAt = s.getUpdatedAt();
        return r;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }

    public String getFrequency() { return frequency; }
    public void setFrequency(String frequency) { this.frequency = frequency; }

    public String getWeekday() { return weekday; }
    public void setWeekday(String weekday) { this.weekday = weekday; }

    public Integer getMonthDay() { return monthDay; }
    public void setMonthDay(Integer monthDay) { this.monthDay = monthDay; }

    public Integer getIntervalValue() { return intervalValue; }
    public void setIntervalValue(Integer intervalValue) { this.intervalValue = intervalValue; }

    public String getIntervalUnit() { return intervalUnit; }
    public void setIntervalUnit(String intervalUnit) { this.intervalUnit = intervalUnit; }

    public Integer getRunHour() { return runHour; }
    public void setRunHour(Integer runHour) { this.runHour = runHour; }

    public Integer getRunMinute() { return runMinute; }
    public void setRunMinute(Integer runMinute) { this.runMinute = runMinute; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }

    public LocalDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(LocalDateTime nextRunAt) { this.nextRunAt = nextRunAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

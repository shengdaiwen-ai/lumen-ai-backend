package com.lumenai.backend.dto;

import com.lumenai.backend.entity.Session;

import java.time.LocalDateTime;

public class SessionResponse {

    private Long id;
    private String title;
    private String projectPath;
    private String mode;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String lastMessage;
    /** 是否由自动化任务生成的会话 */
    private Boolean fromScheduleRun;

    public static SessionResponse from(Session s, String lastMessage, Boolean fromScheduleRun) {
        SessionResponse r = new SessionResponse();
        r.id = s.getId();
        r.title = s.getTitle();
        r.projectPath = s.getProjectPath();
        r.mode = s.getMode();
        r.status = s.getStatus();
        r.createdAt = s.getCreatedAt();
        r.updatedAt = s.getUpdatedAt();
        r.lastMessage = lastMessage;
        r.fromScheduleRun = fromScheduleRun;
        return r;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }

    public Boolean getFromScheduleRun() { return fromScheduleRun; }
    public void setFromScheduleRun(Boolean fromScheduleRun) { this.fromScheduleRun = fromScheduleRun; }
}

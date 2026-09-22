package com.lumenai.backend.dto;

import lombok.Data;

@Data
public class ScheduleCreateRequest {
    private String name;
    /** 每天 / 每周 / 每月 / 间隔触发 */
    private String frequency;
    /** 每周几 (1-7) */
    private String weekday;
    /** 每月第几天 (1-31) */
    private Integer monthDay;
    /** 间隔数值 */
    private Integer intervalValue;
    /** 间隔单位：分钟 / 小时 / 天 */
    private String intervalUnit;
    /** 执行小时 (0-23) */
    private Integer runHour;
    /** 执行分钟 (0-59) */
    private Integer runMinute;
    private String message;
    private String cron;
    private String timezone;
    /**
     * 任务绑定的工作空间 id。
     *
     * <p>自动化页「手动新建」由客户端先建好同名空间再带上；「在对话中创建」由
     * {@code ScheduleTool} 从 {@code AgentContext} 取当前会话的空间自动带上。
     * 传 null = 无空间（任务只能纯聊天）。</p>
     */
    private Long workspaceId;
}

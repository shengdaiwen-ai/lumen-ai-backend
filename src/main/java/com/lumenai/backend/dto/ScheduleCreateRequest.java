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
}

package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务表 schedules
 */
@Data
@TableName("schedules")
public class Schedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    private String name;

    /** 频率：每天 / 每周 / 每月 / 间隔触发 */
    private String frequency;

    /** 每周几，逗号分隔如 "1,3,5"，frequency=每周 时有意义 */
    private String weekday;

    /** 每月第几天 (1-31)，frequency=每月 时有意义 */
    @TableField("month_day")
    private Integer monthDay;

    /** 间隔数值，frequency=间隔触发 时有意义 */
    @TableField("interval_value")
    private Integer intervalValue;

    /** 间隔单位：分钟 / 小时 / 天 */
    @TableField("interval_unit")
    private String intervalUnit;

    /** 执行小时 (0-23) */
    @TableField("run_hour")
    private Integer runHour;

    /** 执行分钟 (0-59) */
    @TableField("run_minute")
    private Integer runMinute;

    private String message;

    private String cron;

    private String timezone;

    /** status: active / paused */
    private String status;

    @TableField("last_run_at")
    private LocalDateTime lastRunAt;

    @TableField("next_run_at")
    private LocalDateTime nextRunAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

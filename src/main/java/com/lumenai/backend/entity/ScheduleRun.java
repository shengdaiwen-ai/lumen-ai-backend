package com.lumenai.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务执行历史 schedule_runs
 */
@Data
@TableName("schedule_runs")
public class ScheduleRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("schedule_id")
    private Long scheduleId;

    @TableField("user_id")
    private Long userId;

    @TableField("session_id")
    private Long sessionId;

    /** 执行状态：running / success / failed */
    private String status;

    /** 触发类型：manual（手动立即执行）/ auto（定时自动执行） */
    @TableField("trigger_type")
    private String triggerType;

    /** 执行结果摘要 */
    private String result;

    /** 错误信息 */
    private String error;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("ended_at")
    private LocalDateTime endedAt;
}

package com.lumenai.backend.dto;

import lombok.Data;

@Data
public class ScheduleUpdateRequest {
    private String name;
    private String status;
    private String message;
    private String frequency;
    private String weekday;
    private Integer monthDay;
    private Integer intervalValue;
    private String intervalUnit;
    private Integer runHour;
    private Integer runMinute;
}

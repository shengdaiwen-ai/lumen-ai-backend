package com.lumenai.backend.dto;

import lombok.Data;

@Data
public class SessionUpdateRequest {
    private String title;
    private String status;
}

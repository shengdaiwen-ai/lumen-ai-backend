package com.lumenai.backend.dto;

import lombok.Data;

@Data
public class SessionCreateRequest {
    private String title;
    private String projectPath;
    private String mode;
}

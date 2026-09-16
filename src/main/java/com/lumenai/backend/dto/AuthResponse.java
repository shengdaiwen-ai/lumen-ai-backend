package com.lumenai.backend.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 登录/注册成功的响应体（不含密码）
 */
@Data
@Builder
public class AuthResponse {

    private Long id;
    private String username;
    private String email;
    private String plan;
    private Integer credits;
    private String permissionMode;   // 全局审批模式
    private String avatarUrl;
    private String token;           // Access Token (2h)
    private long expiresIn;         // Access Token 过期秒数
    private String refreshToken;    // Refresh Token (7d)
    private long refreshExpiresIn;  // Refresh Token 过期秒数
}

package com.lumenai.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 登录/注册成功的响应体（不含密码）
 */
@Data
@Builder
public class AuthResponse {

    private Long id;
    private String username;
    private String email;

    /**
     * 当前生效套餐 code（standard / premium / flagship），由 UserService 查
     * user_subscriptions 推导；<b>无生效订阅为 null</b>。
     */
    private String planCode;

    /** 当前生效套餐名称；无生效订阅为 null（展示兜底由前端做，服务端不硬编码「免费版」文案） */
    private String planName;

    /** 通用积分余额，取自 credit_accounts（唯一权威源），详见 CreditService#generalBalanceOf */
    private BigDecimal credits;
    private BigDecimal balance;      // 账户余额（元），可用于购买套餐
    private String permissionMode;   // 全局审批模式
    private String avatarUrl;
    private String token;           // Access Token (2h)
    private long expiresIn;         // Access Token 过期秒数
    private String refreshToken;    // Refresh Token (7d)
    private long refreshExpiresIn;  // Refresh Token 过期秒数
}

package com.lumenai.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置 — 绑定到 application.yml 的 lumen.jwt.*
 */
@Data
@ConfigurationProperties(prefix = "lumen.jwt")
public class JwtProperties {

    /** 签名密钥（Base64 编码，至少 32 字节） */
    private String secret = "lumen-ai-dev-secret-key-change-me-in-production-please-32bytes";

    /** Access Token 有效期（秒），默认 2 小时 */
    private long expireSeconds = 2L * 60 * 60;

    /** Refresh Token 有效期（秒），默认 7 天 */
    private long refreshExpireSeconds = 7L * 24 * 60 * 60;

    /** HTTP Header 名 */
    private String header = "Authorization";

    /** Header 前缀 */
    private String prefix = "Bearer ";
}

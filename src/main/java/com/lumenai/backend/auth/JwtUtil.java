package com.lumenai.backend.auth;

import com.lumenai.backend.config.JwtProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类 — 基于 jjwt 0.12.x
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtProperties properties;

    private SecretKey getSigningKey() {
        byte[] keyBytes = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /** 生成 Access Token（短期 2h） */
    public String generate(Long userId, String email, String username) {
        long now = System.currentTimeMillis();
        long expire = now + properties.getExpireSeconds() * 1000;

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("username", username)
                .claim("type", "access")
                .issuedAt(new Date(now))
                .expiration(new Date(expire))
                .signWith(getSigningKey())
                .compact();
    }

    /** 生成 Refresh Token（长期 7d） */
    public String generateRefresh(Long userId, String email, String username) {
        long now = System.currentTimeMillis();
        long expire = now + properties.getRefreshExpireSeconds() * 1000;

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("username", username)
                .claim("type", "refresh")
                .issuedAt(new Date(now))
                .expiration(new Date(expire))
                .signWith(getSigningKey())
                .compact();
    }

    /** 判断是否为 Refresh Token */
    public boolean isRefreshToken(String token) {
        try {
            Claims claims = parseClaims(token);
            return "refresh".equals(claims.get("type", String.class));
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析 Token，返回 userId */
    public Long parseUserId(String token) {
        Claims claims = parseClaims(token);
        return Long.valueOf(claims.getSubject());
    }

    /** 解析 Token，返回完整 Claims */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 从 Authorization header 中提取 Token */
    public String extractToken(String header) {
        if (header == null || header.isBlank()) return null;
        String prefix = properties.getPrefix();
        if (header.startsWith(prefix)) {
            return header.substring(prefix.length());
        }
        return header;
    }

    /** 验证 Token 有效性 */
    public boolean validate(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (MalformedJwtException | ExpiredJwtException | UnsupportedJwtException | IllegalArgumentException e) {
            log.debug("JWT validate failed: {}", e.getMessage());
            return false;
        }
    }
}

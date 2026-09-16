package com.lumenai.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OpenClaw Gateway 配置
 * 微信/飞书等通道通过 OpenClaw Gateway（Node.js sidecar）桥接
 */
@Data
@Component
@ConfigurationProperties(prefix = "openclaw")
public class OpenClawProperties {

    /** OpenClaw Gateway 的 HTTP API 地址（默认端口 18789） */
    private String gatewayUrl = "http://127.0.0.1:18789";
}

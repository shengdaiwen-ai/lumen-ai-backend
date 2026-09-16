package com.lumenai.backend.config;

import org.springframework.context.annotation.Configuration;

/**
 * Jackson 配置 — Spring Boot 4 已内置 Jackson 3 自动配置，
 * ObjectMapper Bean 由 JacksonAutoConfiguration 提供，无需手动定义。
 * Jackson 3 已内置 jsr310（JavaTimeInitializer），也不需要额外模块。
 */
@Configuration
public class JacksonConfig {
    // 留空，Jackson 3 自动配置会搞定
}

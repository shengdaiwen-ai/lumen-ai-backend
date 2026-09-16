package com.lumenai.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Lumen AI 自定义配置 — 前缀：lumen
 *
 * 用 @ConfigurationProperties 替代散落的 @Value，
 * 编译时由 spring-boot-configuration-processor 自动生成 metadata，
 * IDEA 即可补全 + 不标黄。
 */
@Data
@ConfigurationProperties(prefix = "lumen")
public class LumenProperties {

    private Ai ai = new Ai();
    private Cors cors = new Cors();

    @Data
    public static class Ai {
        private Agent agent = new Agent();
    }

    @Data
    public static class Agent {
        /** ReAct 循环最大轮数，防止无限循环 */
        private int maxLoopRounds = 20;
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5173");
    }
}

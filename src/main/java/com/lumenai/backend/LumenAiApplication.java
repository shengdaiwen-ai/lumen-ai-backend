package com.lumenai.backend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Lumen AI Backend - Next Generation AI Coding Assistant Server
 *
 * @author Lumen AI Team
 * @since 0.1.0
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.lumenai.backend.mapper")
@ConfigurationPropertiesScan("com.lumenai.backend.config")
public class LumenAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LumenAiApplication.class, args);
    }
}

package com.lumenai.backend.agent.tool;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 工具注册表 — 管理所有内置工具、插件工具、MCP 代理工具
 * <p>
 * 桥接层：把自定义 Tool 接口实现 + Spring AI @Tool 注解对象 统一收集，
 * 既可被旧代码使用（toFunctionDefinitions），也可被 Spring AI ChatClient 使用（toToolCallbacks）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    /**
     * 所有工具 Bean — 目前每个工具同时实现了自定义 Tool 接口 + Spring AI @Tool 注解，
     * 所以这一个 List 就覆盖了全部来源。
     */
    private final List<Tool> tools;

    private List<ToolCallback> cachedCallbacks;

    @PostConstruct
    public void init() {
        // ToolCallbacks.from() 会自动发现每个 Bean 上的 @Tool 注解方法
        this.cachedCallbacks = List.of(ToolCallbacks.from(tools.toArray()));
        log.info("ToolRegistry initialized: {} tools registered", cachedCallbacks.size());
        for (ToolCallback cb : cachedCallbacks) {
            log.info("  + tool: {}", cb.getToolDefinition().name());
        }
    }

    /** 获取 Spring AI 兼容的 ToolCallback 列表 — 传给 ChatClient.tools() */
    public List<ToolCallback> toToolCallbacks() {
        return cachedCallbacks != null ? cachedCallbacks : List.of();
    }

    // ========== 兼容旧接口的方法 ==========

    public Optional<Tool> get(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    public List<Tool> all() {
        return List.copyOf(tools);
    }

    /** 导出为 LLM function calling 的定义格式（兼容旧代码） */
    public List<Map<String, Object>> toFunctionDefinitions() {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (Tool t : tools) {
            Map<String, Object> fn = new HashMap<>();
            fn.put("type", "function");
            Map<String, Object> func = new HashMap<>();
            func.put("name", t.name());
            func.put("description", t.description());
            func.put("parameters", t.parameterSchema());
            fn.put("function", func);
            defs.add(fn);
        }
        return defs;
    }
}

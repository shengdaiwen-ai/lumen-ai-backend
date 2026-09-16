package com.lumenai.backend.agent.tool;

import java.util.Map;

/**
 * Agent 工具接口
 *
 * 所有内置工具 + MCP 代理工具 + 插件工具 都实现此接口
 */
public interface Tool {

    /** 工具唯一名称（对应 LLM function calling 的 name） */
    String name();

    /** 描述 —— LLM 根据此描述判断何时调用 */
    String description();

    /** JSON Schema 参数定义 */
    Map<String, Object> parameterSchema();

    /** 默认权限 */
    Permission requiredPermission();

    /** 是否只读（可并行执行） */
    boolean isReadOnly();

    /** 执行工具 */
    ToolResult execute(ToolContext ctx, Map<String, Object> args);

    // ========== 内部数据结构 ==========

    enum Permission {
        /** 自动允许，不问用户 */
        AUTO_ALLOW,
        /** 每次询问用户 */
        ASK_USER,
        /** 永远拒绝 */
        DENY
    }

    record ToolContext(
            Long sessionId,
            String projectPath,
            Map<String, Object> metadata
    ) {}

    record ToolResult(
            boolean success,
            String output,
            int durationMs,
            String error
    ) {
        public static ToolResult ok(String output, int durationMs) {
            return new ToolResult(true, output, durationMs, null);
        }
        public static ToolResult fail(String error, int durationMs) {
            return new ToolResult(false, null, durationMs, error);
        }
    }
}

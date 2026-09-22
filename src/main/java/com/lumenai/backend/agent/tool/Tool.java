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

    /**
     * 工具执行上下文。
     *
     * <p>🔴 <b>不含任何路径</b>（红线 18）：服务端不接触本机路径，路径解析与沙箱判定
     * 一律在客户端完成。历史上这里有个 {@code projectPath} 字段，是「服务端存会话工作目录」
     * 那一版的残留 —— 在云端部署模型下它既无法校验、又属隐私泄露，已随
     * {@code sessions.project_path} 一并废弃。</p>
     */
    record ToolContext(
            Long sessionId,
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

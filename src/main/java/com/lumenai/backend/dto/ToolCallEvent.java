package com.lumenai.backend.dto;

/**
 * 下发给客户端的工具调用请求（SSE 事件 {@code tool_call} 的载荷）。
 *
 * <p>🔴 <b>不含任何本机路径</b> —— 路径由客户端用 {@code workspaceId} 在自己本地解析
 * （服务端既没有也不知道）。参数 {@code args} 里模型给的路径可能是相对路径，
 * 由客户端以空间根为基准归一。</p>
 *
 * @param callId      本次调用的关联标识（上行 {@code /chat/tool-result/{callId}} 用它解锁）
 * @param name        工具名（Read / Write / Edit / Delete / Glob / Grep / Shell / Exec）
 * @param args        工具参数（模型原始参数）
 * @param workspaceId 空间标识；客户端据此查本地路径映射，查不到即拒绝执行
 * @param deadlineMs  客户端应在多少毫秒内回传；超时服务端判失败（流不悬挂）
 * @param permissionMode 服务端判定的生效权限档位（{@code default} / {@code full}）。
 *        仅作展示与审计用途；客户端是否需要二次确认由本地沙箱策略（文件 / 命令 / 网络）
 *        自行决定 —— 不再与服务端下发值做「取更严格者」比较（FR-26 旧机制已随
 *        空间级权限一并废弃）。
 */
public record ToolCallEvent(
        String callId,
        String name,
        Object args,
        Long workspaceId,
        long deadlineMs,
        String permissionMode
) {
}

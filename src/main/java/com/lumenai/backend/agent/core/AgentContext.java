package com.lumenai.backend.agent.core;

/**
 * 线程级 Agent 运行上下文：本次运行绑定的工作空间。
 *
 * <p>为什么不塞进 {@link com.lumenai.backend.auth.UserContext}：{@code userId} 是
 * <b>请求身份</b>（由 JwtAuthFilter 注入，任何 HTTP 线程都有），而工作空间是
 * <b>本次 Agent 运行</b>的上下文 —— 只有真正跑 agent 循环的那个线程才知道，而且必须是
 * <b>解析后</b>的值（跨设备 / 已移除的空间在 AgentEngine 里已经变成 null）。两者生命周期
 * 不同，混在一起会让「HTTP 线程里读到别人会话的空间」变成可能。</p>
 *
 * <p>⚠️ ThreadLocal 不跨线程：{@code AgentEngine} 是在新线程里跑循环的，
 * 所以必须在那个线程的入口设置（与 {@code UserContext} 同一处）。</p>
 *
 * <p>当前读取者：{@code ScheduleTool} —— 对话里创建自动化任务时，把「当前会话的空间」
 * 一并写进任务。缺了这一步，对话创建的任务永远是无空间任务，运行时本地类工具整体不下发。</p>
 */
public class AgentContext {

    private static final ThreadLocal<Long> WORKSPACE = new ThreadLocal<>();

    public static void setWorkspaceId(Long workspaceId) {
        WORKSPACE.set(workspaceId);
    }

    /** 本次运行绑定的空间；未绑定 / 跨设备 / 非 agent 线程调用均返回 null */
    public static Long getWorkspaceId() {
        return WORKSPACE.get();
    }

    public static void clear() {
        WORKSPACE.remove();
    }
}

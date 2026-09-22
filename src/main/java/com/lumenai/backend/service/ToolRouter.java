package com.lumenai.backend.service;

import java.util.Set;

/**
 * 工具归属路由：判定某个工具是「服务端跑」还是「下发客户端跑」。
 *
 * <p>为什么必须集中一处判定：产品部署在云服务器上，文件与命令类工具在服务端执行毫无意义
 * （相对路径落在服务器 JVM 的工作目录，绝对路径读写的是<b>服务器磁盘</b>）。归属表散落各处
 * 迟早漏一个，漏掉的那个工具就会在错误的机器上动用户的文件。</p>
 *
 * <p>留在服务端的：网络类（WebSearch / WebFetch）、时间、定时任务、交互问答、子 Agent
 * —— 它们不碰本机文件系统。</p>
 */
public final class ToolRouter {

    /**
     * 下发客户端执行的工具（文件系统 + 命令类）。
     *
     * <p>名字必须与服务端工具名一致（`Read` / `Shell` 等），否则 ToolCallback 白名单对不上，
     * 模型看到的是同一个工具名却由不同执行体处理。</p>
     */
    public static final Set<String> CLIENT_TOOLS = Set.of(
            "Read", "Write", "Edit", "Delete", "Glob", "Grep", "Shell", "Exec"
    );

    /** 服务端本地执行的工具（显式列出便于阅读与校验，勿直接用于白名单判断） */
    public static final Set<String> SERVER_TOOLS = Set.of(
            "WebSearch", "WebFetch", "DateTime", "ScheduleTool", "AskUserQuestion", "Agent"
    );

    private ToolRouter() {
    }

    /** 是否属于客户端执行（大小写不敏感：模型偶尔会把工具名写成小写） */
    public static boolean isClientTool(String toolName) {
        return toolName != null && CLIENT_TOOLS.contains(toolName.trim());
    }

    /** Shell 类工具允许更长的超时（构建 / 安装命令） */
    public static boolean isLongRunningTool(String toolName) {
        return toolName != null && ("Shell".equals(toolName.trim()) || "Exec".equals(toolName.trim()));
    }
}

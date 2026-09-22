package com.lumenai.backend.common.exception;

/**
 * 机器可读错误码（与 {@code R.code} 的数字码互补）。
 *
 * <p>为什么要有字符串码：数字码只能表意「400 / 403 / 404」，而前端需要区分
 * 「已达档位上限，引导升级」与「重名，请改名」这类<b>同状态码不同处置</b>的场景。
 * 用 message 文案去匹配是最脆的写法（文案随时改），所以口径必须结构化（同红线 17 的思路）。</p>
 */
public final class ErrorCode {

    private ErrorCode() {
    }

    /** 空间不存在 / 已移除 */
    public static final String WORKSPACE_NOT_FOUND = "WORKSPACE_NOT_FOUND";
    /** 已达当前档位的空间数量上限 */
    public static final String WORKSPACE_LIMIT_EXCEEDED = "WORKSPACE_LIMIT_EXCEEDED";
    /** 同设备下名称重复 */
    public static final String WORKSPACE_NAME_TAKEN = "WORKSPACE_NAME_TAKEN";
    /** 该空间不属于当前设备（跨设备打开会话） */
    public static final String WORKSPACE_NOT_ON_DEVICE = "WORKSPACE_NOT_ON_DEVICE";
    /** 会话未绑定工作空间，本地类工具不可用 */
    public static final String WORKSPACE_REQUIRED = "WORKSPACE_REQUIRED";
    /** 会话已绑定工作空间且不允许更换（创建即锁定，FR-12） */
    public static final String SESSION_WORKSPACE_LOCKED = "SESSION_WORKSPACE_LOCKED";
    /** 工具调用超时（客户端未在 deadline 内回传结果） */
    public static final String TOOL_TIMEOUT = "TOOL_TIMEOUT";
    /** 客户端断连导致工具调用失败 */
    public static final String TOOL_DISCONNECTED = "TOOL_DISCONNECTED";
}

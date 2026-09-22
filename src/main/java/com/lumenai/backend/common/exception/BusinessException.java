package com.lumenai.backend.common.exception;

import lombok.Getter;

/**
 * 业务异常
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    /**
     * 机器可读错误码（见 {@link ErrorCode}）；可为 null。
     *
     * <p>数字 {@code code} 保留给 HTTP 语义（400/403/404…），字符串码给业务分支用 ——
     * 前端拿字符串码决定 UI 处置（引导升级 / 提示改名 / 顶部横幅），不去匹配文案。</p>
     */
    private final String errorCode;

    public BusinessException(String message) {
        this(400, null, message, null);
    }

    public BusinessException(int code, String message) {
        this(code, null, message, null);
    }

    public BusinessException(int code, String message, Throwable cause) {
        this(code, null, message, cause);
    }

    /** 带机器可读错误码的构造（{@code errorCode} 见 {@link ErrorCode}） */
    public BusinessException(int code, String errorCode, String message) {
        this(code, errorCode, message, null);
    }

    public BusinessException(int code, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.errorCode = errorCode;
    }
}

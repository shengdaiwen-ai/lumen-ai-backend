package com.lumenai.backend.common.response;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一 API 响应包装
 *
 * @param <T> 业务数据类型
 */
@Data
public class R<T> implements Serializable {

    private int code;
    private String message;
    private T data;
    private long timestamp = System.currentTimeMillis();

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(200);
        r.setMessage("ok");
        r.setData(data);
        return r;
    }

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }

    public static <T> R<T> fail(String message) {
        return fail(500, message);
    }

    public boolean isSuccess() {
        return code == 200;
    }
}

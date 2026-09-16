package com.lumenai.backend.auth;

/** 线程级用户上下文，由 JwtInterceptor 注入，AgentEngine / Service 链路可用 */
public class UserContext {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    public static void set(Long userId) {
        HOLDER.set(userId);
    }

    public static Long get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}

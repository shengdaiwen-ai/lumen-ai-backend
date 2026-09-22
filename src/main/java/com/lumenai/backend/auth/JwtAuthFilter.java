package com.lumenai.backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Set;

/**
 * JWT 鉴权 Filter — 比 HandlerInterceptor 更底层，不受 WebMvc/WebFlux 分派影响
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final com.lumenai.backend.config.JwtProperties properties;
    private final ObjectMapper objectMapper;

    /** 需要 JWT 的路径 */
    private static final Set<String> AUTH_PATTERNS = Set.of(
            "/api/auth/me",
            "/api/sessions",
            "/api/chat",
            "/api/tools",
            "/api/schedules",
            "/api/schedule-runs",
            "/api/files",
            "/api/profile",
            "/api/subscription",
            "/api/credits",
            "/api/work-assistant/accounts",
            "/api/work-assistant/messages",
            "/api/work-assistant/sessions",
            "/api/work-assistant/reply/stream",
            // 工作空间（选择工作空间需求）—— 新增受保护接口必须登记（红线 10）
            "/api/workspace",
            // 审计中心（P4）：上报 / 列表 / 导出全部需要登录，记录按 JWT 主体隔离
            "/api/audit"
    );

    /** 明确不需要 Access Token 的路径（refresh 自带 refresh token） */
    private static final Set<String> SKIP_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/work-assistant/gateway/callback",  // OpenClaw Gateway 回调无 JWT
            "/api/work-assistant/accounts/confirm",    // Electron 主进程直调，body 传 userId
            "/api/work-assistant/accounts/session-expired", // Electron 主进程直调，用 ilinkUserId 反查
            "/api/work-assistant/messages/inbound"     // Electron 主进程直调，用 ilinkUserId 反查
    );

    private boolean needsAuth(String path) {
        // refresh 等接口跳过 Access Token 鉴权
        if (SKIP_PATHS.contains(path)) return false;
        for (String prefix : AUTH_PATTERNS) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        log.info("[JWT] path={}", path);

        // CORS headers（所有请求都加，包括预检）
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Device-Id");
        response.setHeader("Access-Control-Allow-Credentials", "true");

        // OPTIONS 预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(204);
            return;
        }

        if (!needsAuth(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader(properties.getHeader());
        // 支持 query param token（供 <img src> 等无法带 Authorization header 的场景）
        String queryToken = request.getParameter("token");
        String token = jwtUtil.extractToken(header);
        if (token == null && queryToken != null) {
            token = queryToken;
            log.info("[JWT] using query token, path={}", path);
        }

        if (token == null || !jwtUtil.validate(token)) {
            log.warn("[JWT] validate FAILED, token={}", token);
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    com.lumenai.backend.common.response.R.fail(401, "未登录或 Token 已过期")
            ));
            return;
        }

        Long userId = jwtUtil.parseUserId(token);
        log.info("[JWT] OK, userId={}", userId);
        request.setAttribute("currentUserId", userId);
        UserContext.set(userId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}

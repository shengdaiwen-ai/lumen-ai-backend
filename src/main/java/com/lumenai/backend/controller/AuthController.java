package com.lumenai.backend.controller;

import com.lumenai.backend.auth.JwtUtil;
import com.lumenai.backend.common.response.R;
import com.lumenai.backend.dto.AuthResponse;
import com.lumenai.backend.dto.LoginRequest;
import com.lumenai.backend.dto.RegisterRequest;
import com.lumenai.backend.entity.User;
import com.lumenai.backend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用户认证 API — 注册 / 登录 / 获取当前用户
 */
@Tag(name = "用户认证", description = "注册、登录、获取当前用户信息")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    @Operation(summary = "注册")
    @PostMapping("/register")
    public R<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return R.ok(userService.register(req));
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public R<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return R.ok(userService.login(req));
    }

    @Operation(summary = "获取当前用户信息（需 Token）")
    @GetMapping("/me")
    public R<UserInfo> me(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        User user = userService.getById(userId);
        return R.ok(UserInfo.from(user));
    }

    @Operation(summary = "刷新 Access Token（需 Refresh Token）")
    @PostMapping("/refresh")
    public R<AuthResponse> refresh(@RequestBody RefreshRequest req) {
        String refreshToken = req.getRefreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            return R.fail(401, "Refresh Token 为空");
        }
        if (!jwtUtil.validate(refreshToken) || !jwtUtil.isRefreshToken(refreshToken)) {
            return R.fail(401, "Refresh Token 无效或已过期");
        }
        Long userId = jwtUtil.parseUserId(refreshToken);
        User user = userService.getById(userId);

        String newAccessToken = jwtUtil.generate(user.getId(), user.getEmail(), user.getUsername());
        String newRefreshToken = jwtUtil.generateRefresh(user.getId(), user.getEmail(), user.getUsername());

        return R.ok(AuthResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .plan(user.getPlan())
                .credits(user.getCredits())
                .avatarUrl(user.getAvatarUrl())
                .token(newAccessToken)
                .expiresIn(2L * 60 * 60)
                .refreshToken(newRefreshToken)
                .refreshExpiresIn(7L * 24 * 60 * 60)
                .build());
    }

    /** Refresh Token 请求体 */
    @lombok.Data
    public static class RefreshRequest {
        private String refreshToken;
    }

    /** 不返回密码的用户 DTO */
    public record UserInfo(Long id, String username, String email, String plan, Integer credits, String avatarUrl) {
        public static UserInfo from(User u) {
            return new UserInfo(u.getId(), u.getUsername(), u.getEmail(), u.getPlan(), u.getCredits(), u.getAvatarUrl());
        }
    }
}

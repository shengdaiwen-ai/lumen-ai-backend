package com.lumenai.backend.controller;

import com.lumenai.backend.auth.JwtUtil;
import com.lumenai.backend.common.response.R;
import com.lumenai.backend.dto.AuthResponse;
import com.lumenai.backend.dto.LoginRequest;
import com.lumenai.backend.dto.RegisterRequest;
import com.lumenai.backend.entity.SubscriptionPlan;
import com.lumenai.backend.entity.User;
import com.lumenai.backend.service.ChatBillingService;
import com.lumenai.backend.service.CreditGrantService;
import com.lumenai.backend.service.CreditService;
import com.lumenai.backend.service.SubscriptionService;
import com.lumenai.backend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * 用户认证 API — 注册 / 登录 / 获取当前用户
 *
 * <p>出参里两个"用户画像"字段都不来自 users 表，各自查权威源：
 * {@code credits} → {@link CreditService#generalBalanceOf}（积分账户表）；
 * {@code planCode} / {@code planName} → {@link SubscriptionService#currentActivePlan}（已购订阅表）。
 * users 表原有的 {@code credits} / {@code plan} 两列已于 2026-09-17 删除。</p>
 *
 * <p><b>为什么这里要触发积分补发</b>：积分自 2026-09-18 起按月发放（每月一份月度权益包），
 * 而项目没有定时任务，靠「惰性发放」在真实入口补齐。取用户信息是每次启动/刷新都会走的路径，
 * 在这里补发能保证<b>刚跨过月末的用户一上来就看到本月积分已到账</b>。补发幂等，重复调用安全。</p>
 */
@Slf4j
@Tag(name = "用户认证", description = "注册、登录、获取当前用户信息")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final CreditService creditService;
    private final CreditGrantService creditGrantService;
    private final SubscriptionService subscriptionService;
    private final ChatBillingService chatBillingService;

    /**
     * 惰性清扫僵尸预扣单（进程崩溃/结算异常留下的 {@code held} 单，钱扣着没结算）。
     *
     * <p>与积分按月发放同一套思路：不引定时任务，挂在用户必然会经过的入口上顺手扫一次。
     * 清扫失败不影响主流程。</p>
     */
    private void sweepStaleHolds(Long userId) {
        try {
            chatBillingService.sweepExpiredHolds(userId);
        } catch (Exception e) {
            log.warn("清扫僵尸预扣单失败 userId={}", userId, e);
        }
    }

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
        creditGrantService.grantDue(userId);
        sweepStaleHolds(userId);
        return R.ok(UserInfo.from(user,
                subscriptionService.currentActivePlan(userId),
                creditService.generalBalanceOf(userId)));
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
        creditGrantService.grantDue(userId);
        sweepStaleHolds(userId);
        SubscriptionPlan current = subscriptionService.currentActivePlan(userId);

        String newAccessToken = jwtUtil.generate(user.getId(), user.getEmail(), user.getUsername());
        String newRefreshToken = jwtUtil.generateRefresh(user.getId(), user.getEmail(), user.getUsername());

        return R.ok(AuthResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .planCode(current == null ? null : current.getCode())
                .planName(current == null ? null : current.getName())
                .credits(creditService.generalBalanceOf(user.getId()))
                .balance(user.getBalance())
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

    /** 不返回密码的用户 DTO；credits 与套餐均由调用方查好传入（各有权威源，不从 users 表读） */
    public record UserInfo(Long id, String username, String email,
                           String planCode, String planName, BigDecimal credits,
                           BigDecimal balance, String avatarUrl) {
        public static UserInfo from(User u, SubscriptionPlan current, BigDecimal credits) {
            return new UserInfo(u.getId(), u.getUsername(), u.getEmail(),
                    current == null ? null : current.getCode(),
                    current == null ? null : current.getName(),
                    credits, u.getBalance(), u.getAvatarUrl());
        }
    }
}

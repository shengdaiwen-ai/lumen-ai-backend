package com.lumenai.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumenai.backend.auth.JwtUtil;
import com.lumenai.backend.common.exception.BusinessException;
import com.lumenai.backend.dto.AuthResponse;
import com.lumenai.backend.dto.LoginRequest;
import com.lumenai.backend.dto.RegisterRequest;
import com.lumenai.backend.entity.SubscriptionPlan;
import com.lumenai.backend.entity.User;
import com.lumenai.backend.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 用户认证服务 — 注册、登录、用户信息
 *
 * <p>登录/注册响应里的积分与套餐都来自各自权威源（积分账户表、已购订阅表），
 * 且<b>在取值前先触发一次积分补发</b> —— 积分按月发放且项目没有定时任务，
 * 登录是最自然的补发时机。</p>
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtUtil jwtUtil;
    private final CreditService creditService;
    private final CreditGrantService creditGrantService;
    private final SubscriptionService subscriptionService;

    /** 注册 */
    public AuthResponse register(RegisterRequest req) {
        if (!req.getPassword().equals(req.getConfirmPassword())) {
            throw new BusinessException("两次密码不一致");
        }
        if (userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, req.getEmail())) != null) {
            throw new BusinessException("邮箱已注册");
        }

        User user = new User();
        user.setEmail(req.getEmail());
        // 用户名取邮箱 @ 前的部分
        user.setUsername(req.getEmail().split("@")[0]);
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);

        return buildResponse(user);
    }

    /** 登录 */
    public AuthResponse login(LoginRequest req) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, req.getEmail()));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException("邮箱或密码错误");
        }
        return buildResponse(user);
    }

    /** 根据 ID 获取用户 */
    public User getById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) throw new BusinessException("用户不存在");
        return user;
    }

    private AuthResponse buildResponse(User user) {
        String accessToken = jwtUtil.generate(user.getId(), user.getEmail(), user.getUsername());
        String refreshToken = jwtUtil.generateRefresh(user.getId(), user.getEmail(), user.getUsername());
        // 先把到点未发的月度积分补齐，再取余额 —— 否则跨月首次登录会显示旧余额
        creditGrantService.grantDue(user.getId());
        SubscriptionPlan current = subscriptionService.currentActivePlan(user.getId());
        return AuthResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .planCode(current == null ? null : current.getCode())
                .planName(current == null ? null : current.getName())
                .credits(creditService.generalBalanceOf(user.getId()))
                .balance(user.getBalance())
                .permissionMode(user.getPermissionMode() != null ? user.getPermissionMode() : "smart")
                .avatarUrl(user.getAvatarUrl())
                .token(accessToken)
                .expiresIn(2L * 60 * 60) // 2 小时
                .refreshToken(refreshToken)
                .refreshExpiresIn(7L * 24 * 60 * 60) // 7 天
                .build();
    }
}

package com.lumenai.backend.controller;

import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.common.response.R;
import com.lumenai.backend.entity.User;
import com.lumenai.backend.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 用户设置 / 个人资料接口
 */
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserMapper userMapper;

    private static final Set<String> ALLOWED_MODES =
            Set.of("manual", "smart", "fully", "custom");

    /** 获取当前用户设置 */
    @GetMapping
    public R<Map<String, Object>> get() {
        Long uid = UserContext.get();
        User u = userMapper.selectById(uid);
        Map<String, Object> data = Map.of(
                "permissionMode", u.getPermissionMode() != null ? u.getPermissionMode() : "smart",
                "username", u.getUsername(),
                "email", u.getEmail()
        );
        return R.ok(data);
    }

    /** 更新全局审批模式 */
    @PatchMapping("/permission-mode")
    public R<Map<String, String>> updatePermissionMode(@RequestBody Map<String, String> body) {
        String mode = body.get("permissionMode");
        if (mode == null || !ALLOWED_MODES.contains(mode)) {
            return R.fail(400, "非法的 permissionMode，必须为 manual / smart / fully / custom");
        }
        Long uid = UserContext.get();
        User u = new User();
        u.setId(uid);
        u.setPermissionMode(mode);
        u.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(u);
        return R.ok(Map.of("permissionMode", mode));
    }
}

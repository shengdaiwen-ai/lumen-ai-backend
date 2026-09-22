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

    /**
     * 权限档位（2 档，对齐 WorkBuddy）：
     * <ul>
     *   <li>{@code default} —— 默认权限：会动用户机器的操作（写文件 / 执行命令 / 联网取数）每次都问；</li>
     *   <li>{@code full}    —— 完全访问权限：不再逐次确认。</li>
     * </ul>
     * 旧四档（manual / smart / fully / custom）已废弃，库中存量值由
     * {@code DbMigrationRunner} 启动时迁移。
     */
    private static final Set<String> ALLOWED_MODES = Set.of("default", "full");

    /** 缺省档位：最严格的一档（宁可多问一次，不可默认放行） */
    private static final String DEFAULT_MODE = "default";

    /** 获取当前用户设置 */
    @GetMapping
    public R<Map<String, Object>> get() {
        Long uid = UserContext.get();
        User u = userMapper.selectById(uid);
        Map<String, Object> data = Map.of(
                "permissionMode", normalize(u.getPermissionMode()),
                "username", u.getUsername(),
                "email", u.getEmail()
        );
        return R.ok(data);
    }

    /** 更新全局权限档位 */
    @PatchMapping("/permission-mode")
    public R<Map<String, String>> updatePermissionMode(@RequestBody Map<String, String> body) {
        String mode = body.get("permissionMode");
        if (mode == null || !ALLOWED_MODES.contains(mode)) {
            return R.fail(400, "非法的 permissionMode，必须为 default / full");
        }
        Long uid = UserContext.get();
        User u = new User();
        u.setId(uid);
        u.setPermissionMode(mode);
        u.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(u);
        return R.ok(Map.of("permissionMode", mode));
    }

    /** 历史值兜底：非 full 一律按 default 处理（后端执行侧的判据与此一致） */
    private String normalize(String raw) {
        return "full".equalsIgnoreCase(raw) ? "full" : DEFAULT_MODE;
    }
}

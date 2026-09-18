package com.lumenai.backend.controller;

import com.lumenai.backend.auth.UserContext;
import com.lumenai.backend.common.response.R;
import com.lumenai.backend.dto.CreditOverviewVO;
import com.lumenai.backend.dto.CreditRewardVO;
import com.lumenai.backend.dto.CreditUsageVO;
import com.lumenai.backend.service.ChatBillingService;
import com.lumenai.backend.service.CreditGrantService;
import com.lumenai.backend.service.CreditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 积分接口
 *
 * <p>薄控制器：只做参数收敛与结果包装，业务在 {@link CreditService}。</p>
 *
 * <p><b>为什么要在这里补发积分</b>：积分自 2026-09-18 起改为<b>按月发放</b>（每月一份月度权益包），
 * 而项目没有定时任务。做法是「惰性发放」—— 在用户真正会看积分的入口（总览、奖励列表）
 * 先把到点未发的期数补齐，这样<b>用户看到的余额永远是他应得的那份</b>，
 * 不会出现「登录了但本月积分还没到账」的困惑。补发幂等（唯一键 + 条件更新抢占），重复调用安全。</p>
 */
@RestController
@RequestMapping("/credits")
@RequiredArgsConstructor
public class CreditController {

    private final CreditService creditService;
    private final CreditGrantService creditGrantService;
    private final ChatBillingService chatBillingService;

    /** 积分总览：总可用积分 + 各类型账户余额 */
    @GetMapping("/overview")
    public R<CreditOverviewVO> overview() {
        Long userId = UserContext.get();
        creditGrantService.grantDue(userId);
        chatBillingService.sweepExpiredHolds(userId);
        return R.ok(creditService.getOverview());
    }

    /** 奖励积分列表 */
    @GetMapping("/rewards")
    public R<List<CreditRewardVO>> rewards() {
        Long userId = UserContext.get();
        creditGrantService.grantDue(userId);
        chatBillingService.sweepExpiredHolds(userId);
        return R.ok(creditService.getRewards());
    }

    /**
     * 用量明细分页查询
     *
     * @param range today / 7d / 30d，不传表示不限时间
     */
    @GetMapping("/usage")
    public R<CreditUsageVO> usage(@RequestParam(defaultValue = "1") Integer pageNum,
                                  @RequestParam(defaultValue = "10") Integer pageSize,
                                  @RequestParam(required = false) String range) {
        return R.ok(creditService.getUsage(pageNum, pageSize, range));
    }
}

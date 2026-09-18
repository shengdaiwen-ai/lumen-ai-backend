package com.lumenai.backend.controller;

import com.lumenai.backend.common.response.R;
import com.lumenai.backend.dto.CreateSubscriptionOrderRequest;
import com.lumenai.backend.dto.SubscriptionOrderVO;
import com.lumenai.backend.dto.SubscriptionPlanVO;
import com.lumenai.backend.dto.SubscriptionPreviewVO;
import com.lumenai.backend.service.SubscriptionOrderService;
import com.lumenai.backend.service.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订阅套餐接口
 *
 * <p>薄控制器：只做参数收敛与结果包装，业务在 Service。新增接口都在 {@code /subscription} 前缀下，
 * 已被鉴权白名单规则覆盖，无需额外登记。</p>
 */
@RestController
@RequestMapping("/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final SubscriptionOrderService subscriptionOrderService;

    /** 获取订阅套餐列表（含权益与计费周期）。只读查询，幂等。 */
    @GetMapping("/plans")
    public R<List<SubscriptionPlanVO>> plans() {
        return R.ok(subscriptionService.getPlans());
    }

    /**
     * 升级折算预览（只读，无副作用）。
     *
     * <p>供弹框在切换套餐/周期时展示「原价 / 抵扣 / 应付」与折算依据；
     * <b>所有金额与比例由服务端计算</b>，前端只格式化。下单时会用同一算法重算，预览值不参与成交。</p>
     */
    @GetMapping("/preview")
    public R<SubscriptionPreviewVO> preview(@RequestParam String planCode,
                                            @RequestParam String billingCode) {
        return R.ok(subscriptionService.preview(planCode, billingCode));
    }

    /**
     * 余额支付下单：折算 → 扣余额 → 生成订单 → 开通/升级/续费 → 记流水 → 发积分 → 回收旧积分
     * （全部在同一事务）。
     *
     * <p>入参只带套餐与周期的 code，<b>金额由服务端按库表价格计算</b>；
     * {@code confirmWasted} 仅在本次会作废抵扣额时必需（服务端拒绝静默截断）。</p>
     */
    @PostMapping("/orders")
    public R<SubscriptionOrderVO> createOrder(@Valid @RequestBody CreateSubscriptionOrderRequest req) {
        return R.ok(subscriptionOrderService.payWithBalance(
                req.getPlanCode(), req.getBillingCode(), req.getConfirmWasted()));
    }
}

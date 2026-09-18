package com.lumenai.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 余额支付下单请求（POST /subscription/orders 入参）
 *
 * <p><b>只传套餐与计费周期的 code，不传金额</b> —— 金额一律由服务端按当前库表价格计算，
 * 客户端传价格会被篡改（付 0.01 元买旗舰版）。</p>
 */
@Data
public class CreateSubscriptionOrderRequest {

    /** 套餐标识，如 standard / premium / flagship */
    @NotBlank(message = "请选择套餐")
    private String planCode;

    /** 计费周期标识，如 monthly-auto / yearly */
    @NotBlank(message = "请选择计费周期")
    private String billingCode;

    /**
     * 是否已确认「周期缩短导致抵扣作废」。
     *
     * <p>仅当预览返回 {@code wastedDiscount > 0} 时必填 {@code true} —— 服务端把它当作
     * 「用户已看过作废告警并确认」的凭据。<b>为什么不能只靠前端弹框</b>：
     * 前端漏传、绕过界面直调接口、或将来有人重构掉了这个弹层，都会退化成「静默截断」——
     * 用户被作废了钱却毫不知情。把它做成服务端必填项，这条规则就不再依赖任何前端自觉。</p>
     */
    private Boolean confirmWasted;
}

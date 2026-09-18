package com.lumenai.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumenai.backend.entity.SubscriptionCreditGrant;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订阅积分发放计划 Mapper（零 XML，条件构造器查询）
 *
 * <p>本表不需要「带参自运算」的注解 SQL：发放的幂等靠
 * <b>条件更新</b>（{@code WHERE status = 'pending'} → 置 {@code granted}，0 行即表示已被别的请求发过）
 * 实现，用 {@code LambdaUpdateWrapper} 即可表达。</p>
 */
@Mapper
public interface SubscriptionCreditGrantMapper extends BaseMapper<SubscriptionCreditGrant> {
}

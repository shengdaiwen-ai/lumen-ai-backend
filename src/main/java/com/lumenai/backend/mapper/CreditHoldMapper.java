package com.lumenai.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumenai.backend.entity.CreditHold;
import org.apache.ibatis.annotations.Mapper;

/**
 * 预扣单 Mapper（零 XML）
 *
 * <p>状态抢占（{@code held → settled/refunded/expired}）用 {@code LambdaUpdateWrapper}
 * 的条件更新即可表达（带 {@code WHERE status = 'held'} 返回影响行数），
 * 不需要注解 SQL —— 注解 SQL 只留给「带参自运算」那一类。</p>
 */
@Mapper
public interface CreditHoldMapper extends BaseMapper<CreditHold> {
}

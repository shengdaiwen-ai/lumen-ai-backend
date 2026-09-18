package com.lumenai.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumenai.backend.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 原子扣减账户余额（条件更新，规范 §10.3）。
     *
     * <p>条件 {@code balance >= #{amount}} 与扣减在同一条 UPDATE 里完成，
     * 并发下不会扣穿（禁止「先 SELECT 判断再 UPDATE」——两个请求会同时读到足够余额）。</p>
     *
     * <p>唯一一处用注解 SQL 的 Mapper：MyBatis-Plus 的 {@code LambdaUpdateWrapper}
     * 做不了带参数的 {@code x = x - ?} 自减（{@code setSql} 需要拼字符串）。
     * 注解仍属「零 Mapper XML」，参数走 {@code #{}} 预编译，无注入口。</p>
     *
     * @return 影响行数：<b>1 = 扣款成功，0 = 余额不足（或用户不存在）</b>
     */
    @Update("UPDATE users SET balance = balance - #{amount} WHERE id = #{userId} AND balance >= #{amount}")
    int deductBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);
}

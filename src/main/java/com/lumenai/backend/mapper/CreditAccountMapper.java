package com.lumenai.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumenai.backend.entity.CreditAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 积分账户 Mapper（零 XML，条件构造器查询）
 */
@Mapper
public interface CreditAccountMapper extends BaseMapper<CreditAccount> {

    /**
     * 积分入账：自增与条件同一条 SQL，并发下不会丢更新。
     *
     * <p>与 {@code UserMapper#deductBalance} 同属「带参自运算」——MyBatis-Plus 的条件构造器
     * 只能拼字面量 SQL（{@code setSql}），做不了带参自减/自增，故此处用注解 SQL（仍属零 XML）。</p>
     *
     * @return 影响行数；0 表示账户不存在（调用方应先确保账户已创建）
     */
    @Update("UPDATE credit_accounts SET balance = balance + #{amount} " +
            "WHERE user_id = #{userId} AND credit_type = #{creditType}")
    int addBalance(@Param("userId") Long userId,
                   @Param("creditType") String creditType,
                   @Param("amount") BigDecimal amount);

    /**
     * 积分条件扣减（预扣阶段专用）：扣减与余额下限校验同一条 SQL，余额不够就一行都不改。
     *
     * <p>与 {@link #addBalance} 互补：预扣要求「余额 ≥ 预扣额才放行」，所以必须带
     * {@code AND balance >= #{amount}} —— 禁止「先 SELECT 判断再 UPDATE」（并发下会扣穿）。</p>
     *
     * <p>预扣多算了要退还、少算了要补扣，两个方向的差额都用 {@code addBalance}（传负数即减，
     * 无下限校验）完成，因此本类是<b>全项目注解 SQL 的 4 处之二</b>。</p>
     *
     * @return 影响行数：<b>0 表示余额不足预扣额</b>（调用方据此拒绝本次请求）
     */
    @Update("UPDATE credit_accounts SET balance = balance - #{amount} " +
            "WHERE user_id = #{userId} AND credit_type = #{creditType} AND balance >= #{amount}")
    int deductCredits(@Param("userId") Long userId,
                      @Param("creditType") String creditType,
                      @Param("amount") BigDecimal amount);
}

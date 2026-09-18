package com.lumenai.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumenai.backend.entity.CreditReward;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 奖励积分 Mapper
 *
 * <p>全项目仅四处使用注解 SQL（仍属「零 Mapper XML」），都是<b>条件构造器表达不了的带参自运算</b>：
 * {@code UserMapper#deductBalance}（余额扣减）、{@code CreditAccountMapper#addBalance}（积分入账）、
 * {@code CreditAccountMapper#deductCredits}（预扣时的条件扣减），以及本类的 {@link #reclaim}
 * （批次剩余回收）。</p>
 */
@Mapper
public interface CreditRewardMapper extends BaseMapper<CreditReward> {

    /**
     * 回收批次剩余积分：自减与条件同一条 SQL，并发下不会扣穿，也不会出现「先查再减」的丢失更新。
     *
     * <p>升级场景下调用（旧套餐当月批次的未消耗部分按折算比例收回）。
     * 参数不变量保证它不会扣穿：回收量 {@code = total × min(t, u) ≤ remaining}
     * —— 因为 {@code min(t, u) ≤ u = remaining / total}。</p>
     *
     * @return 影响行数：<b>0 表示批次不存在或剩余不足</b>，调用方必须抛异常回滚整笔交易
     *         （绝不允许「钱退了、积分没收回」这种半结算状态）
     */
    @Update("UPDATE credit_rewards SET remaining = remaining - #{amount} " +
            "WHERE id = #{rewardId} AND remaining >= #{amount}")
    int reclaim(@Param("rewardId") Long rewardId, @Param("amount") BigDecimal amount);
}

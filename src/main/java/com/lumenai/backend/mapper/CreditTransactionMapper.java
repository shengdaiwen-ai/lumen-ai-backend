package com.lumenai.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumenai.backend.entity.CreditTransaction;
import org.apache.ibatis.annotations.Mapper;

/**
 * 积分流水 Mapper（零 XML，条件构造器查询）
 */
@Mapper
public interface CreditTransactionMapper extends BaseMapper<CreditTransaction> {
}

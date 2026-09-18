package com.lumenai.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumenai.backend.entity.BalanceTransaction;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BalanceTransactionMapper extends BaseMapper<BalanceTransaction> {
}

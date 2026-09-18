package com.lumenai.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumenai.backend.entity.ModelPricing;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型计价 Mapper（零 XML，条件构造器查询）
 */
@Mapper
public interface ModelPricingMapper extends BaseMapper<ModelPricing> {
}

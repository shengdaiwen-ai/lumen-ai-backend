package com.lumenai.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumenai.backend.entity.Memory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemoryMapper extends BaseMapper<Memory> {
}

package com.lumenai.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumenai.backend.entity.Session;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionMapper extends BaseMapper<Session> {
}

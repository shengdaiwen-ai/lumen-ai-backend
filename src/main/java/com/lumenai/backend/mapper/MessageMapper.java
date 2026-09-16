package com.lumenai.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumenai.backend.entity.Message;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}

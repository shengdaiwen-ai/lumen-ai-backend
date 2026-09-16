package com.lumenai.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumenai.backend.entity.Schedule;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScheduleMapper extends BaseMapper<Schedule> {
}

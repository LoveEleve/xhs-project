package com.myxhs.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.notification.entity.PushTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PushTemplateMapper extends BaseMapper<PushTemplate> {

    @Select("SELECT * FROM t_push_template WHERE type = #{type} AND status = 1")
    PushTemplate selectByType(String type);
}

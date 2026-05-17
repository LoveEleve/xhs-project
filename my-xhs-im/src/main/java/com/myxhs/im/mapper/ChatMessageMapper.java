package com.myxhs.im.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.im.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}

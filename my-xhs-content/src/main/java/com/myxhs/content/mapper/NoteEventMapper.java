package com.myxhs.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.myxhs.content.entity.NoteEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 笔记状态事件流水 Mapper（append-only）
 */
@Mapper
public interface NoteEventMapper extends BaseMapper<NoteEvent> {
}

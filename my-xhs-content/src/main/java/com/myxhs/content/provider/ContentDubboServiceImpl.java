package com.myxhs.content.provider;

import com.myxhs.common.response.PageResult;
import com.myxhs.content.api.dubbo.ContentDubboService;
import com.myxhs.content.dto.response.CommentVO;
import com.myxhs.content.dto.response.NoteDetailVO;
import com.myxhs.content.service.CommentService;
import com.myxhs.content.service.NoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容 Dubbo Provider 实现（Triple 协议）
 * <p>
 * 替代 Feign 调用，提供高性能的笔记/评论查询 RPC 接口。
 * 主要用于首页聚合服务的 Feed 流、笔记详情、评论分页等场景。
 * </p>
 */
@Slf4j
@Component
@DubboService
@RequiredArgsConstructor
public class ContentDubboServiceImpl implements ContentDubboService {

    private final NoteService noteService;
    private final CommentService commentService;

    @Override
    public Map<String, Object> getNoteDetail(Long noteId) {
        NoteDetailVO vo = noteService.getNoteDetail(noteId);
        return voToMap(vo);
    }

    @Override
    public Map<String, Object> getCommentPage(Long noteId, int pageNum, int pageSize) {
        PageResult<CommentVO> pageResult = commentService.getCommentPage(noteId, pageNum, pageSize);
        Map<String, Object> result = new HashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("pages", pageResult.getPages());
        result.put("records", pageResult.getRecords());
        result.put("current", pageResult.getPageNum());
        result.put("size", pageResult.getPageSize());
        return result;
    }

    @Override
    public Map<String, Object> getUserNotes(Long userId, int pageNum, int pageSize) {
        // NoteService 没有直接暴露 getUserNotes 方法，通过 NoteMapper 查询
        // 暂时返回空结果，后续对接 NoteService 的分页查询方法
        log.warn("[ContentDubbo] getUserNotes 待对接 NoteService 分页方法: userId={}", userId);
        Map<String, Object> result = new HashMap<>();
        result.put("records", List.of());
        result.put("total", 0);
        return result;
    }

    private Map<String, Object> voToMap(Object vo) {
        Map<String, Object> map = new HashMap<>();
        if (vo == null) return map;
        try {
            for (var field : vo.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                map.put(field.getName(), field.get(vo));
            }
        } catch (Exception e) {
            log.warn("[ContentDubbo] VO 转 Map 异常", e);
        }
        return map;
    }
}

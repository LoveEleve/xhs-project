package com.myxhs.home.feign;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.fallback.ContentFeignFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 内容服务 Feign Client
 */
@FeignClient(name = "my-xhs-content",
        fallbackFactory = ContentFeignFallbackFactory.class)
public interface ContentFeignClient {

    /**
     * 获取笔记详情
     */
    @GetMapping("/api/note/detail/{id}")
    R<Map<String, Object>> getNoteDetail(@PathVariable("id") Long noteId);

    /**
     * P2-3: 批量获取笔记详情（Feed 场景）
     */
    @PostMapping("/api/note/batch-detail")
    R<Map<String, Object>> batchGetNoteDetail(@RequestBody java.util.List<Long> noteIds);

    /**
     * 评论分页查询
     */
    @GetMapping("/api/comment/page/{noteId}")
    R<Map<String, Object>> getCommentPage(@PathVariable("noteId") Long noteId,
                                          @RequestParam("pageNum") int pageNum,
                                          @RequestParam("pageSize") int pageSize);

    /**
     * 获取指定用户的笔记列表（公开接口，仅已发布）
     */
    @GetMapping("/api/note/user/{userId}")
    R<Map<String, Object>> getUserNotes(@PathVariable("userId") Long userId,
                                        @RequestParam("pageNum") int pageNum,
                                        @RequestParam("pageSize") int pageSize);
}

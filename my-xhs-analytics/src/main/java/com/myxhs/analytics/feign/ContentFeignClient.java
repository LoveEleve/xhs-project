package com.myxhs.analytics.feign;

import com.myxhs.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * O-Like-1 修复（2026-08-13）：内容服务内部客户端——点赞通知链路取笔记作者/标题。
 * X-Internal-Call 由 FeignInternalCallInterceptor 全局注入。
 * <p>
 * 用批量详情而非单条详情：批量接口无 VIEW 计数副作用（O1 修复后语义），
 * 避免点赞动作虚增浏览计数。
 * </p>
 */
@FeignClient(name = "my-xhs-content", configuration = InternalCallFeignConfig.class)
public interface ContentFeignClient {

    /**
     * 批量获取笔记详情（返回 Map<noteId字符串, 详情>；已删/草稿条目降级跳过）
     */
    @PostMapping("/api/note/batch-detail")
    R<Map<String, Object>> batchGetNoteDetail(@RequestBody List<Long> noteIds);

    /**
     * O-Like-3/4：评论信息（存在性 + 作者 + 所属笔记）——X-Internal-Call 保护
     */
    @GetMapping("/api/comment/internal/info/{commentId}")
    R<Map<String, Object>> getCommentInfo(@PathVariable("commentId") Long commentId);
}

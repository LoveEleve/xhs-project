package com.myxhs.home.feign.fallback;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.ContentFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Component
public class ContentFeignFallbackFactory implements FallbackFactory<ContentFeignClient> {
    @Override
    public ContentFeignClient create(Throwable cause) {
        log.warn("[降级] ContentFeignClient 不可用: {}", cause.getMessage());
        return new ContentFeignClient() {
            @Override
            public R<Map<String, Object>> getNoteDetail(Long noteId) {
                return R.ok(Collections.emptyMap());
            }

            @Override
            public R<Map<String, Object>> getCommentPage(Long noteId, int pageNum, int pageSize) {
                return R.ok(Collections.emptyMap());
            }

            @Override
            public R<Map<String, Object>> getUserNotes(Long userId, int pageNum, int pageSize) {
                return R.ok(Collections.emptyMap());
            }
        };
    }
}

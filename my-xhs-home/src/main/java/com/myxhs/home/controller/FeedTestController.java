package com.myxhs.home.controller;

import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.response.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * Feed 测试接口（仅开发环境可用）
 * <p>
 * 安全原则：测试接口绝对不能暴露在生产环境中。
 * 使用 @Profile("dev") 确保只在开发 Profile 激活时注册这些接口。
 * 生产环境启动时使用 --spring.profiles.active=prod，这些接口不会被加载。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/home/test")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated
@Profile("dev")
public class FeedTestController {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 模拟推送笔记到用户收件箱（测试用）
     */
    @PostMapping("/push-inbox")
    public R<Void> testPushInbox(
            @RequestParam Long userId,
            @RequestParam Long noteId,
            @RequestParam(required = false) Long publishTime) {
        if (publishTime == null) {
            publishTime = System.currentTimeMillis();
        }
        String inboxKey = RedisKeyConstants.FEED_INBOX + userId;
        stringRedisTemplate.opsForZSet().add(inboxKey, String.valueOf(noteId), publishTime);
        log.info("[测试] 推送到收件箱: userId={}, noteId={}, score={}", userId, noteId, publishTime);
        return R.ok();
    }

    /**
     * 模拟写入大V发件箱（测试用）
     */
    @PostMapping("/push-outbox")
    public R<Void> testPushOutbox(
            @RequestParam Long authorId,
            @RequestParam Long noteId,
            @RequestParam(required = false) Long publishTime) {
        if (publishTime == null) {
            publishTime = System.currentTimeMillis();
        }
        String outboxKey = RedisKeyConstants.FEED_OUTBOX + authorId;
        stringRedisTemplate.opsForZSet().add(outboxKey, String.valueOf(noteId), publishTime);
        log.info("[测试] 写入发件箱: authorId={}, noteId={}, score={}", authorId, noteId, publishTime);
        return R.ok();
    }
}

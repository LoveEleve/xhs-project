package com.myxhs.common.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 版本门辅助（C-009 D3：完成态语义——对齐 C-008）
 * <p>
 * 语义（两个动作配套使用）：
 * <ul>
 *   <li>{@link #isStale(String, Long)}：**只读**检查——已推进版本 ≥ 事件时间 → 旧事件（调用方跳过）；</li>
 *   <li>{@link #markDone(String, Long, long)}：业务**完成后**单调推进（只增不减）。</li>
 * </ul>
 * </p>
 * <p>
 * 与旧实现（reserve-first：检查+SET 一步完成 + 异常删键）的区别：旧实现在"SET 后、业务完成前"
 * 崩溃（kill -9 等）会把重投拦死——事件永久丢失；完成态语义下崩溃无推进 → 重投重新处理
 * （业务幂等兜底：唯一索引 / DELETE / set-based / msgId dedup）。
 * </p>
 * <p>
 * 并发安全：markDone 用 Lua 单调推进（只增不减）——并发旧事件不会把版本回退；
 * 同一事件的并发投递由业务幂等收敛（本辅助只负责"顺序门"与"完成标记"）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VersionGateHelper {

    private final StringRedisTemplate stringRedisTemplate;

    /** 单调推进 Lua（只增不减；EX 续期） */
    private static final DefaultRedisScript<Long> ADVANCE_SCRIPT = new DefaultRedisScript<>(
            "local cur = tonumber(redis.call('get', KEYS[1]) or '0') " +
            "local v = tonumber(ARGV[1]) " +
            "if cur < v then redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2]) end " +
            "return cur",
            Long.class);

    /**
     * 只读检查旧事件：已推进版本 ≥ 事件时间 → true（调用方跳过）。
     * <p>不置位——处理中崩溃无推进，重投可重新处理。</p>
     *
     * @param eventTime 事件时间戳（null 或 ≤0 → 不拦截，兼容旧格式事件）
     */
    public boolean isStale(String key, Long eventTime) {
        if (eventTime == null || eventTime <= 0) {
            return false;
        }
        try {
            String v = stringRedisTemplate.opsForValue().get(key);
            if (v == null) {
                return false;
            }
            return Long.parseLong(v) >= eventTime;
        } catch (NumberFormatException e) {
            return false; // 脏数据不拦截
        }
    }

    /** 业务完成后单调推进（失败仅告警——幂等由业务兜底，下轮重投再收敛） */
    public void markDone(String key, Long eventTime, long ttlSeconds) {
        if (eventTime == null || eventTime <= 0) {
            return;
        }
        try {
            stringRedisTemplate.execute(ADVANCE_SCRIPT,
                    java.util.List.of(key), String.valueOf(eventTime), String.valueOf(ttlSeconds));
        } catch (Exception e) {
            log.warn("[版本门] 推进失败(幂等不受影响): key={}, eventTime={}", key, eventTime, e);
        }
    }
}

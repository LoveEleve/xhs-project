package com.myxhs.payment.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 模拟支付宝交易台账仓储（Redis 存储层）
 * <p>
 * 键设计：
 * <ul>
 *   <li>{@code myxhs:payment:mock-alipay:trade:{outTradeNo}}：台账记录（JSON）</li>
 *   <li>{@code myxhs:payment:mock-alipay:refund:{outRequestNo}}：退款幂等结果
 *       （官方语义"同 out_request_no 只退一次"）</li>
 *   <li>{@code myxhs:payment:mock-alipay:lock:{outTradeNo}}：交易操作锁（TTL 兜底，多实例互斥）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockAlipayTradeRepository {

    static final String TRADE_PREFIX = "myxhs:payment:mock-alipay:trade:";
    static final String REFUND_PREFIX = "myxhs:payment:mock-alipay:refund:";
    static final String LOCK_PREFIX = "myxhs:payment:mock-alipay:lock:";

    /** 交易操作锁 TTL（秒）——与既有模拟器同口径：TTL 兜底，不主动释放 */
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 保存台账记录（整体覆盖写；调用方须持有交易锁） */
    public void save(MockTradeRecord record) {
        try {
            redisTemplate.opsForValue().set(TRADE_PREFIX + record.getOutTradeNo(),
                    objectMapper.writeValueAsString(record));
        } catch (Exception e) {
            throw new IllegalStateException("模拟台账写入失败: " + record.getOutTradeNo(), e);
        }
    }

    /** 读取台账记录；不存在/解析失败 → {@code null} */
    public MockTradeRecord load(String outTradeNo) {
        String json = redisTemplate.opsForValue().get(TRADE_PREFIX + outTradeNo);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MockTradeRecord.class);
        } catch (Exception e) {
            log.warn("[模拟台账] 记录解析失败（按不存在处理）: outTradeNo={}", outTradeNo, e);
            return null;
        }
    }

    /** 扫描全部台账记录（SCAN 批处理，避免 keys 阻塞） */
    public List<MockTradeRecord> scanAll() {
        List<MockTradeRecord> records = new ArrayList<>();
        try (var cursor = redisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(TRADE_PREFIX + "*")
                        .count(100)
                        .build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                MockTradeRecord record = load(key.substring(TRADE_PREFIX.length()));
                if (record != null) {
                    records.add(record);
                }
            }
        }
        return records;
    }

    /** 交易操作锁（setIfAbsent + TTL）；获取失败 = 其他线程/实例处理中 */
    public boolean tryLock(String outTradeNo) {
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_PREFIX + outTradeNo, "1", LOCK_TTL);
        return Boolean.TRUE.equals(locked);
    }

    /** 读取退款幂等结果（同 outRequestNo 的既有结果）；不存在 → {@code null} */
    public com.myxhs.payment.alipay.RefundTradeResult loadRefundResult(String outRequestNo) {
        String json = redisTemplate.opsForValue().get(REFUND_PREFIX + outRequestNo);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, com.myxhs.payment.alipay.RefundTradeResult.class);
        } catch (Exception e) {
            log.warn("[模拟台账] 退款幂等结果解析失败（按不存在处理）: outRequestNo={}", outRequestNo, e);
            return null;
        }
    }

    /** 保存退款幂等结果（"同号只退一次"） */
    public void saveRefundResult(String outRequestNo, com.myxhs.payment.alipay.RefundTradeResult result) {
        try {
            redisTemplate.opsForValue().set(REFUND_PREFIX + outRequestNo,
                    objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            throw new IllegalStateException("模拟台账退款结果写入失败: " + outRequestNo, e);
        }
    }
}

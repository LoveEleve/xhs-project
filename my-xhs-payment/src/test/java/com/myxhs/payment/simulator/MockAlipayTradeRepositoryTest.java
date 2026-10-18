package com.myxhs.payment.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.payment.alipay.RefundTradeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模拟支付宝交易台账仓储单测（Redis 以 mock 替代）
 */
@SuppressWarnings("unchecked")
class MockAlipayTradeRepositoryTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ObjectMapper objectMapper;
    private MockAlipayTradeRepository repository;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        objectMapper = new ObjectMapper();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        repository = new MockAlipayTradeRepository(redisTemplate, objectMapper);
    }

    private MockTradeRecord record() {
        MockTradeRecord record = new MockTradeRecord();
        record.setOutTradeNo("PAY1");
        record.setTradeNo("ALIPAY_TRADE_001");
        record.setAmount("199.00");
        record.setRefundedAmount("0.00");
        record.setStatus("WAIT_BUYER_PAY");
        return record;
    }

    @Test
    @DisplayName("save：JSON 写入台账键")
    void saveWritesJson() {
        repository.save(record());

        verify(valueOps).set(eq(MockAlipayTradeRepository.TRADE_PREFIX + "PAY1"), anyString());
    }

    @Test
    @DisplayName("load：JSON 解析回记录；不存在 → null")
    void loadParsesJson() throws Exception {
        MockTradeRecord record = record();
        when(valueOps.get(MockAlipayTradeRepository.TRADE_PREFIX + "PAY1"))
                .thenReturn(objectMapper.writeValueAsString(record));

        MockTradeRecord loaded = repository.load("PAY1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getOutTradeNo()).isEqualTo("PAY1");
        assertThat(loaded.getTradeNo()).isEqualTo("ALIPAY_TRADE_001");
        assertThat(loaded.getStatus()).isEqualTo("WAIT_BUYER_PAY");

        when(valueOps.get(MockAlipayTradeRepository.TRADE_PREFIX + "PAY9")).thenReturn(null);
        assertThat(repository.load("PAY9")).isNull();
    }

    @Test
    @DisplayName("scanAll：SCAN 游标 → 记录列表")
    void scanAllReturnsRecords() throws Exception {
        MockTradeRecord record = record();
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(MockAlipayTradeRepository.TRADE_PREFIX + "PAY1");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(valueOps.get(MockAlipayTradeRepository.TRADE_PREFIX + "PAY1"))
                .thenReturn(objectMapper.writeValueAsString(record));

        List<MockTradeRecord> records = repository.scanAll();

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getOutTradeNo()).isEqualTo("PAY1");
    }

    @Test
    @DisplayName("tryLock：setIfAbsent 成功/失败语义")
    void tryLockSemantics() {
        when(valueOps.setIfAbsent(eq(MockAlipayTradeRepository.LOCK_PREFIX + "PAY1"), eq("1"), any(Duration.class)))
                .thenReturn(true);
        assertThat(repository.tryLock("PAY1")).isTrue();

        when(valueOps.setIfAbsent(eq(MockAlipayTradeRepository.LOCK_PREFIX + "PAY2"), eq("1"), any(Duration.class)))
                .thenReturn(false);
        assertThat(repository.tryLock("PAY2")).isFalse();
    }

    @Test
    @DisplayName("退款幂等结果：保存与读取 roundtrip")
    void refundResultRoundTrip() throws Exception {
        RefundTradeResult result = RefundTradeResult.success("R1");
        repository.saveRefundResult("REQ1", result);
        verify(valueOps).set(eq(MockAlipayTradeRepository.REFUND_PREFIX + "REQ1"), anyString());

        when(valueOps.get(MockAlipayTradeRepository.REFUND_PREFIX + "REQ1"))
                .thenReturn(objectMapper.writeValueAsString(result));
        RefundTradeResult loaded = repository.loadRefundResult("REQ1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.isFundChange()).isTrue();
        assertThat(loaded.getTradeNo()).isEqualTo("R1");
    }
}

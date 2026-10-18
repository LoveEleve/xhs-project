package com.myxhs.search.consumer;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家子单索引同步消费者单测（C-004）
 * <p>
 * 验证口径：canal 消息（JSON）→ 文档映射/删除语义/幂等重放/失败补偿；
 * ES 写入以 Writer mock 断言（ES 胶水在 Writer 内）。
 * </p>
 */
@SuppressWarnings("unchecked")
class SellerSubOrderIndexSyncConsumerTest {

    private static final long TS = 1715510539000L;
    private static final String SUB_ORDER_NO = "ORDER-1001-1";

    private SellerSubOrderIndexWriter indexWriter;
    private StringRedisTemplate redisTemplate;
    private SetOperations<String, String> setOps;
    private SellerSubOrderIndexSyncConsumer consumer;

    @BeforeEach
    void setUp() {
        indexWriter = mock(SellerSubOrderIndexWriter.class);
        redisTemplate = mock(StringRedisTemplate.class);
        setOps = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        consumer = new SellerSubOrderIndexSyncConsumer(indexWriter, redisTemplate);
    }

    private MessageExt message(String json) {
        MessageExt msg = new MessageExt();
        msg.setBody(json.getBytes(StandardCharsets.UTF_8));
        return msg;
    }

    private String canalMessage(String table, String type, Map<String, Object> row) {
        JSONObject msg = new JSONObject();
        msg.put("database", "my_xhs_order_0");
        msg.put("table", table);
        msg.put("type", type);
        JSONArray data = new JSONArray();
        data.add(row);
        msg.put("data", data);
        msg.put("ts", TS);
        return msg.toJSONString();
    }

    private Map<String, Object> row() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", "100");
        row.put("order_id", "1001");
        row.put("order_no", "ORDER-1001");
        row.put("sub_order_no", SUB_ORDER_NO);
        row.put("user_id", "2001");
        row.put("seller_id", "3001");
        row.put("status", "2");
        row.put("total_amount", "199.00");
        row.put("discount_amount", "9.00");
        row.put("pay_amount", "190.00");
        row.put("created_at", "2026-10-17 12:00:00");
        row.put("updated_at", "2026-10-17 12:30:00");
        row.put("deleted", "0");
        return row;
    }

    @Test
    @DisplayName("INSERT：列名→文档字段映射 + 版本（ts）+ doc id=subOrderNo")
    void insertUpsertsDocument() throws Exception {
        consumer.onMessage(message(canalMessage("t_sub_order_0", "INSERT", row())));

        ArgumentCaptor<Map<String, Object>> docCaptor = ArgumentCaptor.forClass(Map.class);
        verify(indexWriter).upsert(eq(SUB_ORDER_NO), docCaptor.capture(), eq(TS));
        Map<String, Object> doc = docCaptor.getValue();
        assertThat(doc.get("subOrderNo")).isEqualTo(SUB_ORDER_NO);
        assertThat(doc.get("orderNo")).isEqualTo("ORDER-1001");
        assertThat(doc.get("orderId")).isEqualTo(1001L);
        assertThat(doc.get("userId")).isEqualTo(2001L);
        assertThat(doc.get("sellerId")).isEqualTo(3001L);
        assertThat(doc.get("status")).isEqualTo(2);
        assertThat(doc.get("payAmount").toString()).isEqualTo("190.00");
        assertThat(doc.get("createdAt")).isEqualTo("2026-10-17 12:00:00");
        assertThat(doc.get("deleted")).isEqualTo(false);
    }

    @Test
    @DisplayName("UPDATE 重放幂等：同消息两次 → 两次同 id 同版本 upsert（ExternalGte 兜底不重复）")
    void updateReplayIsIdempotent() throws Exception {
        String json = canalMessage("t_sub_order_1", "UPDATE", row());
        consumer.onMessage(message(json));
        consumer.onMessage(message(json));

        verify(indexWriter, times(2)).upsert(eq(SUB_ORDER_NO), anyMap(), eq(TS));
        verify(indexWriter, never()).softDelete(anyString(), anyLong());
    }

    @Test
    @DisplayName("DELETE 事件 → 软删除标记")
    void deleteEventSoftDeletes() throws Exception {
        consumer.onMessage(message(canalMessage("t_sub_order_2", "DELETE", row())));

        verify(indexWriter).softDelete(SUB_ORDER_NO, TS);
        verify(indexWriter, never()).upsert(anyString(), anyMap(), anyLong());
    }

    @Test
    @DisplayName("逻辑删除（UPDATE deleted=1）→ 软删除标记（同 product 索引模式）")
    void logicalDeleteSoftDeletes() throws Exception {
        Map<String, Object> deletedRow = row();
        deletedRow.put("deleted", "1");
        consumer.onMessage(message(canalMessage("t_sub_order_0", "UPDATE", deletedRow)));

        verify(indexWriter).softDelete(SUB_ORDER_NO, TS);
        verify(indexWriter, never()).upsert(anyString(), anyMap(), anyLong());
    }

    @Test
    @DisplayName("非法消息（空体/格式不符/空 data/非子单表）→ 跳过不重试")
    void invalidMessagesSkipped() throws Exception {
        consumer.onMessage(message("{}"));
        consumer.onMessage(message("{\"table\":\"t_sub_order_0\"}"));

        JSONObject emptyData = new JSONObject();
        emptyData.put("table", "t_sub_order_0");
        emptyData.put("data", new JSONArray());
        consumer.onMessage(message(emptyData.toJSONString()));

        consumer.onMessage(message(canalMessage("t_order_0", "INSERT", row())));

        verify(indexWriter, never()).upsert(anyString(), anyMap(), anyLong());
        verify(indexWriter, never()).softDelete(anyString(), anyLong());
    }

    @Test
    @DisplayName("缺 sub_order_no 的行 → 跳过（不写 ES）")
    void rowWithoutSubOrderNoSkipped() throws Exception {
        Map<String, Object> noNo = row();
        noNo.remove("sub_order_no");
        consumer.onMessage(message(canalMessage("t_sub_order_0", "INSERT", noNo)));

        verify(indexWriter, never()).upsert(anyString(), anyMap(), anyLong());
    }

    @Test
    @DisplayName("ES 写入失败 → 失败单号落 Redis 补偿键 + 上抛（触发 MQ 重试）")
    void esFailureRecordsCompensationAndRetries() throws Exception {
        doThrow(new IOException("ES down")).when(indexWriter).upsert(anyString(), anyMap(), anyLong());

        assertThatThrownBy(() -> consumer.onMessage(message(canalMessage("t_sub_order_0", "INSERT", row()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("卖家子单索引同步失败");

        verify(setOps).add(eq(SellerSubOrderIndexSyncConsumer.FAILED_KEY), eq(SUB_ORDER_NO));
        verify(redisTemplate).expire(eq(SellerSubOrderIndexSyncConsumer.FAILED_KEY), any(Duration.class));
    }
}

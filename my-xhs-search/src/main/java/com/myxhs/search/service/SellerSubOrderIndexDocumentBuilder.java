package com.myxhs.search.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 商家子单索引文档构建器（C-004）
 * <p>
 * 单一事实来源：<b>增量（canal binlog 行，字符串值）</b>与<b>存量（order 域扫描行，对象值）</b>
 * 共用同一字段映射——保证两条写入路径的文档结构完全一致。类型宽容：String/Number/LocalDateTime 均可。
 * </p>
 * <p>
 * 正确性守卫：ES 只做发现；写操作/终态以 DB + CAS 为准。
 * </p>
 */
@Slf4j
@Component
public class SellerSubOrderIndexDocumentBuilder {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 数据库行（snake_case 列名）→ ES 文档（字段名与 seller_sub_order_index mapping 对应） */
    public Map<String, Object> build(Map<String, Object> dbRow) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("subOrderNo", toStr(dbRow.get("sub_order_no")));
        doc.put("orderNo", toStr(dbRow.get("order_no")));
        doc.put("orderId", toLong(dbRow.get("order_id")));
        doc.put("userId", toLong(dbRow.get("user_id")));
        doc.put("sellerId", toLong(dbRow.get("seller_id")));
        doc.put("status", toInt(dbRow.get("status")));
        doc.put("totalAmount", toDecimal(dbRow.get("total_amount")));
        doc.put("discountAmount", toDecimal(dbRow.get("discount_amount")));
        doc.put("payAmount", toDecimal(dbRow.get("pay_amount")));
        doc.put("createdAt", toDateTimeString(dbRow.get("created_at")));
        doc.put("updatedAt", toDateTimeString(dbRow.get("updated_at")));
        doc.put("deleted", isDeleted(dbRow.get("deleted")));
        return doc;
    }

    /**
     * 文档版本（回填用）：updated_at → epoch 毫秒。
     * <p>
     * <b>必须与 canal ts（毫秒）同量级</b>——否则复现 T-088 的"ExternalGte 增量冻结"陷阱
     * （小整数版本写过文档后，后续毫秒版本事件被永久拒绝）。
     * </p>
     */
    public long versionOf(Map<String, Object> dbRow) {
        String dateTime = toDateTimeString(dbRow.get("updated_at"));
        if (dateTime != null) {
            try {
                return LocalDateTime.parse(dateTime, DATE_TIME)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception e) {
                log.warn("[卖家子单索引] updated_at 解析失败（版本回退当前时间）: {}", dateTime);
            }
        }
        return System.currentTimeMillis();
    }

    // ==================== 类型宽容转换 ====================

    private String toStr(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isDeleted(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() == 1;
        }
        String s = String.valueOf(value);
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    /** 归一为 "yyyy-MM-dd HH:mm:ss"（canal 字符串 / ISO 字符串 / LocalDateTime 均可） */
    private String toDateTimeString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dt) {
            return dt.format(DATE_TIME);
        }
        String s = String.valueOf(value);
        if (s.isBlank()) {
            return null;
        }
        if (s.indexOf('T') > 0) {
            try {
                String iso = s.substring(0, Math.min(19, s.length()));
                return LocalDateTime.parse(iso).format(DATE_TIME);
            } catch (Exception ignored) {
                // 非标准 ISO：原样返回（ES date 字段另有容错格式）
            }
        }
        return s;
    }
}

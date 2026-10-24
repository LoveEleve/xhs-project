package com.myxhs.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 搜索服务公共基类
 * <p>
 * 抽取 NoteSearchService 和 ProductSearchService 中的公共方法：
 * - parseSearchAfter：解析 Search After 游标（区分 double/long，防止浮点精度丢失）
 * - normalizeSize：规范化分页大小
 * - toLong/toBigDecimal：类型转换工具方法
 * </p>
 * <p>
 * 为什么不直接用静态工具类？
 * 1. 这些方法依赖 @Value 配置（defaultPageSize/maxPageSize），属于实例状态
 * 2. 模板方法模式预留扩展点，子类可以覆盖默认行为
 * 3. 未来可能添加公共的查询日志、熔断等横切关注点
 * </p>
 */
@Slf4j
public abstract class AbstractSearchService {

    /**
     * 序列化 Search After 游标（提取 FieldValue 实际值）
     * <p>
     * 修复：直接 JSON.toJSONString(hit.sort()) 会把 FieldValue 对象整体序列化
     * （如 {"any":false,"double":true,"long":false,...}），
     * 与 parseSearchAfter 期望的数字/字符串数组不匹配，导致翻页返回空。
     * 必须提取 FieldValue 的实际值（double/long/string）后再序列化。
     * </p>
     */
    protected String serializeSearchAfter(List<FieldValue> sortValues) {
        if (sortValues == null || sortValues.isEmpty()) {
            return null;
        }
        List<Object> values = sortValues.stream()
                .map(this::encodeFieldValue)
                .collect(Collectors.toList());
        return JSON.toJSONString(values);
    }

    /**
     * 带类型标签编码：{"t":"d","v":1.0} / {"t":"l","v":10086} ...
     * <p>
     * 不能只输出裸数字：JSON 不保留 double/long 类型，1.0 与 1 无法区分，
     * 会导致 Search After 对 _score/price（double）翻页时类型不匹配。
     * </p>
     */
    private Object encodeFieldValue(FieldValue fv) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        if (fv == null || fv.isNull()) {
            encoded.put("t", "n");
            return encoded;
        }
        if (fv.isDouble()) {
            encoded.put("t", "d");
            encoded.put("v", fv.doubleValue());
        } else if (fv.isLong()) {
            encoded.put("t", "l");
            encoded.put("v", fv.longValue());
        } else if (fv.isBoolean()) {
            encoded.put("t", "b");
            encoded.put("v", fv.booleanValue());
        } else if (fv.isString()) {
            encoded.put("t", "s");
            encoded.put("v", fv.stringValue());
        } else {
            encoded.put("t", "s");
            encoded.put("v", fv.toString());
        }
        return encoded;
    }

    /**
     * 解析 Search After 游标
     * <p>
     * 关键：区分 double 和 long 类型的 FieldValue。
     * _score 是浮点数，如果转为 long 会导致精度丢失，Search After 分页跳过记录。
     * createdAt/likeCount/noteId 等是整数，使用 longValue() 正确。
     * </p>
     */
    protected List<FieldValue> parseSearchAfter(String searchAfter) {
        List<Object> values = JSON.parseArray(searchAfter, Object.class);
        return values.stream()
                .map(this::decodeFieldValue)
                .collect(Collectors.toList());
    }

    /**
     * 解析游标：优先类型标签（新格式）；裸数字走旧格式启发式（兼容历史游标）。
     */
    private FieldValue decodeFieldValue(Object v) {
        if (v instanceof JSONObject obj && obj.containsKey("t")) {
            String type = obj.getString("t");
            return switch (type) {
                case "d" -> FieldValue.of(obj.getDoubleValue("v"));
                case "l" -> FieldValue.of(obj.getLongValue("v"));
                case "b" -> FieldValue.of(obj.getBooleanValue("v"));
                case "n" -> FieldValue.NULL;
                default -> FieldValue.of(obj.getString("v"));
            };
        }
        // 旧格式兼容：裸数字只能靠启发式（整数值 double 会被判 long——历史游标的已知边界）
        if (v instanceof Number num) {
            if (num.doubleValue() != num.longValue()) {
                return FieldValue.of(num.doubleValue());
            }
            return FieldValue.of(num.longValue());
        }
        if (v instanceof String str) {
            return FieldValue.of(str);
        }
        if (v instanceof Boolean bool) {
            return FieldValue.of(bool);
        }
        return FieldValue.of(String.valueOf(v));
    }

    /**
     * 规范化分页大小
     */
    protected int normalizeSize(Integer size, int defaultSize, int maxSize) {
        if (size == null || size <= 0) return defaultSize;
        return Math.min(size, maxSize);
    }

    protected Long toLong(Object obj) {
        if (obj == null) return 0L;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try { return Long.parseLong(obj.toString()); } catch (Exception e) { return 0L; }
    }

    protected BigDecimal toBigDecimal(Object obj) {
        if (obj == null) return BigDecimal.ZERO;
        if (obj instanceof Number) return BigDecimal.valueOf(((Number) obj).doubleValue());
        try { return new BigDecimal(obj.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
}

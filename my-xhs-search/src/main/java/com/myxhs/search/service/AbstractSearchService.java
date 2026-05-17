package com.myxhs.search.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
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
                .map(v -> {
                    if (v instanceof Number) {
                        Number num = (Number) v;
                        // 判断是否为浮点数：double 值与 long 值不同时说明有小数部分
                        if (num.doubleValue() != num.longValue()) {
                            return FieldValue.of(num.doubleValue());
                        }
                        return FieldValue.of(num.longValue());
                    } else if (v instanceof String) {
                        return FieldValue.of((String) v);
                    } else {
                        return FieldValue.of(v.toString());
                    }
                })
                .collect(Collectors.toList());
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

package com.myxhs.ai.app.eval;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EvalAsserter 数字一致性抽取（M6 软断言）：序号/日期/证据引用不参与比对。
 */
class EvalAsserterTest {

    @Test
    void 枚举序号不抽取() {
        String answer = "可能原因包括：1) 积压为瞬时高水位；2) 采集为时点快照；3) 生产消费失衡。"
                + "另外：4、缓存未命中；5. 依赖下游慢。";
        Set<String> nums = EvalAsserter.extractNonPercentNumbers(answer);
        assertTrue(nums.isEmpty(), "序号不应被抽取: " + nums);
    }

    @Test
    void 常规业务数字保留() {
        String answer = "订单量从 46 降到 21，退款金额 199.80 元";
        Set<String> nums = EvalAsserter.extractNonPercentNumbers(answer);
        assertEquals(Set.of("46", "21", "199.80"), nums);
    }

    @Test
    void 小数后点加空格不误杀() {
        // "1.5 倍" 是整体小数，不应被当序号；只有 "1. 序号" 形态才跳过
        Set<String> nums = EvalAsserter.extractNonPercentNumbers("增长 1.5 倍，占比 2.0%");
        assertTrue(nums.contains("1.5"));
        assertFalse(nums.contains("2.0"), "百分比数字不参与比对");
    }

    @Test
    void 日期与证据引用不抽取() {
        String answer = "2026-08-15 采集，asOf 07:42:53，证据 ev_a1b2c3d4e5f60708090a0b0c0d0e0f10 显示 0 积压";
        Set<String> nums = EvalAsserter.extractNonPercentNumbers(answer);
        assertEquals(Set.of("0"), nums);
    }
}

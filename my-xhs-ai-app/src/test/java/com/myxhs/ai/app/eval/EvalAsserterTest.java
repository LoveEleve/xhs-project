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
        String answer = "可能原因包括：1) 积压为瞬时高水位；2）采集为时点快照；3) 生产消费失衡。"
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
    void 秒和毫秒换算视为一致() {
        assertTrue(EvalAsserter.approxEquals(0.553, 553.0));
        assertTrue(EvalAsserter.approxEquals(553.0, 0.553));
    }

    @Test
    void 小数后点加空格不误杀() {
        // "1.5 倍" 是整体小数，不应被当序号；只有 "1. 序号" 形态才跳过
        Set<String> nums = EvalAsserter.extractNonPercentNumbers("增长 1.5 倍，占比 2.0%");
        assertTrue(nums.contains("1.5"));
        assertFalse(nums.contains("2.0"), "百分比数字不参与比对");
    }

    @Test
    void Markdown表格和列表序号不抽取() {
        Set<String> nums = EvalAsserter.extractNonPercentNumbers(
                "| 排名 | 服务 | 延迟 |\n| 1 | gateway | 0.553s |\n| 2 | gateway | 0.082s |");
        assertTrue(nums.contains("0.553"));
        assertTrue(nums.contains("0.082"));
        assertFalse(nums.contains("1"));
        assertFalse(nums.contains("2"));
    }

    @Test
    void Top和排名序号不抽取() {
        Set<String> nums = EvalAsserter.extractNonPercentNumbers(
                "Top 3 为慢端点；排名 1 是 health，排名 2 是 UNKNOWN，排名 3 是 /**");
        assertTrue(nums.isEmpty(), "Top/排名序号不应参与比对: " + nums);
    }

    @Test
    void 窗口和日期数字不抽取() {
        Set<String> nums = EvalAsserter.extractNonPercentNumbers(
                "最近 7 天，查询跨度最长为 31 天；2025 年 1 月数据不可用。");
        assertTrue(nums.isEmpty(), "窗口/日期数字不应参与比对: " + nums);
    }

    @Test
    void 日期与证据引用不抽取() {
        String answer = "2026-08-15 采集，asOf 07:42:53，证据 ev_a1b2c3d4e5f60708090a0b0c0d0e0f10 显示 0 积压";
        Set<String> nums = EvalAsserter.extractNonPercentNumbers(answer);
        assertEquals(Set.of("0"), nums);
    }
}
